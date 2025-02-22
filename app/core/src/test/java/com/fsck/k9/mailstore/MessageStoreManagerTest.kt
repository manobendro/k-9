package com.fsck.k9.mailstore

import com.fsck.k9.Account
import com.fsck.k9.AccountRemovedListener
import com.fsck.k9.preferences.AccountManager
import com.google.common.truth.Truth.assertThat
import com.nhaarman.mockitokotlin2.KStubbing
import com.nhaarman.mockitokotlin2.argumentCaptor
import com.nhaarman.mockitokotlin2.doNothing
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.whenever
import org.junit.Test

class MessageStoreManagerTest {
    private val account = Account("00000000-0000-4000-0000-000000000000")
    private val messageStore1 = mock<MessageStore>(name = "messageStore1")
    private val messageStore2 = mock<MessageStore>(name = "messageStore2")
    private val messageStoreFactory = mock<MessageStoreFactory> {
        on { create(account) } doReturn messageStore1 doReturn messageStore2
    }

    @Test
    fun `MessageStore instance is reused`() {
        val accountManager = mock<AccountManager>()
        val messageStoreManager = MessageStoreManager(accountManager, messageStoreFactory)

        assertThat(messageStoreManager.getMessageStore(account)).isSameInstanceAs(messageStore1)
        assertThat(messageStoreManager.getMessageStore(account)).isSameInstanceAs(messageStore1)
    }

    @Test
    fun `MessageStore instance is removed when account is removed`() {
        val listenerCaptor = argumentCaptor<AccountRemovedListener>()
        val accountManager = mock<AccountManager> {
            doNothingOn { addAccountRemovedListener(listenerCaptor.capture()) }
        }
        val messageStoreManager = MessageStoreManager(accountManager, messageStoreFactory)

        assertThat(messageStoreManager.getMessageStore(account)).isSameInstanceAs(messageStore1)

        listenerCaptor.firstValue.onAccountRemoved(account)

        assertThat(messageStoreManager.getMessageStore(account)).isSameInstanceAs(messageStore2)
    }

    private fun <T> KStubbing<T>.doNothingOn(block: T.() -> Any) {
        doNothing().whenever(mock).block()
    }
}
