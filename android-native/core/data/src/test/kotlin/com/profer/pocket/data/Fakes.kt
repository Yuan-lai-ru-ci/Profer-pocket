package com.profer.pocket.data

import com.profer.pocket.data.net.SocketTransport
import com.profer.pocket.data.net.SocketTransportFactory
import com.profer.pocket.data.settings.SettingsStore
import com.profer.pocket.data.store.CredentialStore
import com.profer.pocket.data.store.ResumeStateStore
import com.profer.pocket.data.store.SessionSnapshotStore
import com.profer.pocket.model.PocketSettings

class FakeSocketTransport : SocketTransport {
    private var listener: SocketTransport.Listener? = null
    val sent = mutableListOf<String>()
    var lastUrl: String? = null
    var closedWith: Pair<Int, String>? = null
    var sendResult = true

    override fun connect(url: String, listener: SocketTransport.Listener) {
        lastUrl = url
        this.listener = listener
    }

    override fun send(text: String): Boolean {
        sent += text
        return sendResult
    }

    override fun close(code: Int, reason: String) {
        closedWith = code to reason
    }

    fun open() {
        listener?.onOpen()
    }

    fun receive(text: String) {
        listener?.onText(text)
    }

    fun closeFromServer(code: Int, reason: String) {
        listener?.onClosed(code, reason)
    }

    fun fail(message: String) {
        listener?.onFailure(message)
    }
}

class FakeSocketFactory : SocketTransportFactory {
    val created = mutableListOf<FakeSocketTransport>()

    override fun create(): SocketTransport = FakeSocketTransport().also { created += it }
}

class FakeResumeStateStore : ResumeStateStore {
    private val cursors = mutableMapOf<String, ResumeStateStore.Cursor>()
    val cleared = mutableListOf<String>()

    override fun load(endpoint: String): ResumeStateStore.Cursor? = cursors[endpoint]

    override fun save(endpoint: String, lastEventId: Long, serverInstanceId: String?) {
        cursors[endpoint] = ResumeStateStore.Cursor(lastEventId, serverInstanceId)
    }

    override fun clear(endpoint: String) {
        cursors.remove(endpoint)
        cleared += endpoint
    }
}

class FakeCredentialStore : CredentialStore {
    var saved: CredentialStore.SavedConnection? = null

    override fun load(): CredentialStore.SavedConnection? = saved

    override fun save(endpoint: String, token: String) {
        saved = CredentialStore.SavedConnection(endpoint, token)
    }

    override fun clear() {
        saved = null
    }
}

class FakeSnapshotStore(
    private var snapshot: SessionSnapshotStore.Snapshot = SessionSnapshotStore.Snapshot(),
) : SessionSnapshotStore {
    var saves = 0

    override fun load(): SessionSnapshotStore.Snapshot = snapshot

    override fun save(snapshot: SessionSnapshotStore.Snapshot) {
        this.snapshot = snapshot
        saves += 1
    }
}

class FakeSettingsStore(var current: PocketSettings = PocketSettings()) : SettingsStore {
    override fun load(): PocketSettings = current

    override fun save(settings: PocketSettings) {
        current = settings
    }
}
