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

/**
 * 账户相关的ViewoModel
 */
class AccountViewModel : ViewModel() {

    private val log = Logger("Account")
    private val blockaRepository = BlockaRepository
    private val persistence = PersistenceService
    private val alert = AlertDialogService
    private val connectivity = ConnectivityService

    // 保存 当前的账户信息
    private val _account = MutableLiveData<Account>()
    val account: LiveData<Account> = _account

    // 保存当前账户 过期的时间
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
                // 从服务端 读取 账户信息
                val account = blockaRepository.fetchAccount(accountId)
                log.w("restoreAccount: after fetch account with accountId, account = $accountId")
                // 将其 存储到 本地，并更新 LiveData
                updateLiveData(account)
            } catch (ex: BlokadaException) {
                log.e("restoreAccount: Failed restoring account".cause(ex))
                updateLiveData(persistence.load(Account::class))
                alert.showAlert(R.string.error_account_inactive_after_restore)
            }
        }
    }

    // 刷新 账户状态
    // 从服务端 获取 账户信息，如果 出现异常，则加载本地的账户信息
    fun refreshAccount() {
        viewModelScope.launch {
            try {
                log.v("refreshAccount: Refreshing account")
                refreshAccountInternal()
            } catch (ex: BlokadaException) {
                when {
                    // 没网
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

    // 检查 是否存在 Account，不存在，则创建新的
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

    // 从本地 加载 账户信息，能加载到，则表明有账户信息
    private fun hasAccount() = try {
        persistence.load(Account::class)
        true
    } catch (ex: Exception) {
        false
    }

    // 调用 服务端接口，创建新的账户信息，然后将 账户信息 存储到本地，并更新LiveData
    private suspend fun createAccount(): Account {
        log.w("createAccount: Creating new account")
        val account = blockaRepository.createAccount()
        log.w("createAccount: account = $account")
        updateLiveData(account)
        return account
    }

    // 从服务端 读取 账户信息，并将其 更新到本地
    private suspend fun refreshAccountInternal(): Account {
        // 从 LiveData中读取 accountID，或者 从 本地加载 Account信息
        val accountId = _account.value?.id ?: persistence.load(Account::class).id
        log.v("refreshAccountInternal: accountId = $accountId")

        // 通过 retrofit读取 账户信息
        val account = blockaRepository.fetchAccount(accountId)
        log.v("refreshAccountInternal: after fetchAccount: account = $account")

        // 将 从 服务器上 获取到的 账户信息 更新到本地 和 LiveData中
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