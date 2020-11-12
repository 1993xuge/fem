/*
 * This file is part of Blokada.
 *
 * Blokada is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Blokada is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Blokada.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright © 2020 Blocka AB. All rights reserved.
 *
 * @author Karol Gusak (karol@blocka.net)
 */

package ui

import androidx.lifecycle.*
import kotlinx.coroutines.launch
import model.Account
import model.AccountId
import model.ActiveUntil
import model.BlokadaException
import org.blokada.R
import repository.BlockaRepository
import service.AlertDialogService
import service.ConnectivityService
import service.PersistenceService
import ui.utils.cause
import utils.Logger

class AccountViewModel : ViewModel() {

    private val log = Logger("Account")
    private val blockaRepository = BlockaRepository
    private val persistence = PersistenceService
    private val alert = AlertDialogService
    private val connectivity = ConnectivityService

    private val _account = MutableLiveData<Account>()
    val account: LiveData<Account> = _account
    val accountExpiration: LiveData<ActiveUntil> =
        _account.map { it.active_until }.distinctUntilChanged()

    init {
        viewModelScope.launch {
            log.v("Refreshing account after start")
            refreshAccount()
        }
    }

    fun restoreAccount(accountId: AccountId) {
        viewModelScope.launch {
            log.w("restoreAccount: accountId = $accountId")
            try {
                val account = blockaRepository.fetchAccount(accountId)
                log.w("restoreAccount: after fetch account with accountId, account = $accountId")
                updateLiveData(account)
            } catch (ex: BlokadaException) {
                log.e("restoreAccount: Failed restoring account".cause(ex))
                updateLiveData(persistence.load(Account::class))
                alert.showAlert(R.string.error_account_inactive_after_restore)
            }
        }
    }

    fun refreshAccount() {
        viewModelScope.launch {
            try {
                log.v("refreshAccount: Refreshing account")
                refreshAccountInternal()
            } catch (ex: BlokadaException) {
                when {
                    connectivity.isDeviceInOfflineMode() ->
                        log.w("refreshAccount: Could not refresh account but device is offline, ignoring")
                    else -> {
                        log.w("refreshAccount: Could not refresh account, TODO".cause(ex))
                    }
                }

                try {
                    log.v("refreshAccount: Returning persisted copy")

                    // 从本地 获取 Account信息
                    updateLiveData(persistence.load(Account::class))
                } catch (ex: Exception) {
                    log.w("refreshAccount: load Account Exception ".cause(ex))
                }
            }
        }
    }

    fun checkAccount() {
        log.w("checkAccount")
        viewModelScope.launch {
            val hasAccount = hasAccount()
            log.w("checkAccount: hasAccount = $hasAccount")
            if (!hasAccount)
                try {
                    log.w("checkAccount: createAccount")
                    createAccount()
                } catch (ex: Exception) {
                    log.w("checkAccount: Could not create account".cause(ex))
                    alert.showAlert(R.string.error_creating_account)
                }
        }
    }

    private fun hasAccount() = try {
        persistence.load(Account::class)
        true
    } catch (ex: Exception) {
        false
    }

    private suspend fun createAccount(): Account {
        log.w("createAccount: Creating new account")
        val account = blockaRepository.createAccount()
        log.w("createAccount: account = $account")
        updateLiveData(account)
        return account
    }

    private suspend fun refreshAccountInternal(): Account {
        val accountId = _account.value?.id ?: persistence.load(Account::class).id
        log.v("refreshAccountInternal: accountId = $accountId")

        val account = blockaRepository.fetchAccount(accountId)
        log.v("refreshAccountInternal: after fetchAccount: account = $account")

        updateLiveData(account)
        log.v("refreshAccountInternal: Account refreshed")

        return account
    }

    private fun updateLiveData(account: Account) {
        log.w("updateLiveData: update Account LiveData And Save it")
        persistence.save(account)
        viewModelScope.launch {
            _account.value = account
        }
    }

}