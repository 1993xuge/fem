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
import model.ActiveUntil
import service.ExpirationService
import service.PersistenceService
import utils.Logger
import java.util.*

class ActivationViewModel: ViewModel() {

    enum class ActivationState {
        INACTIVE,
        PURCHASING,
        JUST_PURCHASED,
        JUST_ACTIVATED,
        ACTIVE,
        JUST_EXPIRED
    }

    private val log = Logger("Activation")
    private val persistence = PersistenceService

    private val expiration = ExpirationService

    private val _state = MutableLiveData<ActivationState>()
    val state: LiveData<ActivationState> = _state.distinctUntilChanged()

    init {
        viewModelScope.launch {
            // 先 加载 ActivationState，默认是 INACTIVE
            _state.value = persistence.load(ActivationState::class)
        }
        expiration.onExpired = {
            setExpiration(Date(0))
        }
    }

    fun setExpiration(activeUntil: ActiveUntil) {
        viewModelScope.launch {
            _state.value?.let { state ->

                // 还未 过期
                val active = !activeUntil.beforeNow()
                when {
                    !active && state != ActivationState.INACTIVE -> {
                        log.w("Account just expired")
                        // 过期了，但是 之前的状态 不是 INACTIVE，那么 需要将 状态迁移成 JUST_EXPIRED
                        updateLiveData(ActivationState.JUST_EXPIRED)
                    }
                    active && state == ActivationState.INACTIVE -> {
                        // 未过期，但是 之前的状态时 INACTIVE，表明 刚刚激活
                        log.w("Account is active")
                        updateLiveData(ActivationState.ACTIVE)
                    }
                    active && state == ActivationState.PURCHASING -> {
                        // 未过期，但是 直接的状态是  购买中，
                        log.w("Account is active after purchase flow, activation succeeded")
                        updateLiveData(ActivationState.JUST_ACTIVATED)
                    }
                    active && state == ActivationState.JUST_PURCHASED -> {
                        log.w("Account is active after refresh, activation succeeded")
                        updateLiveData(ActivationState.JUST_ACTIVATED)
                    }
                    active && state == ActivationState.JUST_EXPIRED -> {
                        log.w("Account got activated after just expired, a bit weird case")
                        updateLiveData(ActivationState.ACTIVE)
                    }
                }

                if (active) expiration.setExpirationAlarm(activeUntil)
            }
        }
    }

    // 将 状态设置为 正在 购买
    fun setStartedPurchaseFlow() {
        viewModelScope.launch {
            log.w("User started purchase flow")
            updateLiveData(ActivationState.PURCHASING)
        }
    }

    // 刷新账户信息，在 访问 url之后
    fun maybeRefreshAccountAfterUrlVisited(url: String) {
        viewModelScope.launch {
            _state.value?.let { state ->
                // If we notice our payment gateway loaded the success url, we refresh account info
                if (state == ActivationState.INACTIVE && url == SUCCESS_URL) {
                    log.w("Payment succeeded, marking account to refresh")
                    // 状态是 未激活，但是 url是 Success，表明 刚刚 购买
                    updateLiveData(ActivationState.JUST_PURCHASED)
                }
            }
        }
    }

    fun maybeRefreshAccountAfterOnResume() {
        viewModelScope.launch {
            _state.value?.let { state ->
                if (state == ActivationState.PURCHASING) {
                    // Assume user might just have purchased (no harm if we are wrong)
                    log.w("User is back from purchase flow, marking account to refresh")
                    updateLiveData(ActivationState.JUST_PURCHASED)
                }
            }
        }
    }

    fun setInformedUserAboutActivation() {
        viewModelScope.launch {
            log.w("Informed user about activation, setting to active")
            updateLiveData(ActivationState.ACTIVE)
        }
    }

    fun setInformedUserAboutExpiration() {
        viewModelScope.launch {
            log.w("Informed user about expiration, setting to inactive")
            updateLiveData(ActivationState.INACTIVE)
        }
    }

    private fun updateLiveData(state: ActivationState) {
        viewModelScope.launch {
            _state.value = state
            persistence.save(state)
        }
    }
}

fun Date.beforeNow(): Boolean {
    return this.before(Date())
}

private const val SUCCESS_URL = "https://app.blokada.org/success"
