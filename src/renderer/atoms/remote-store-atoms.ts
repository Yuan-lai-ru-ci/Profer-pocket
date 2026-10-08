import { atom } from 'jotai'
import type { RemoteStoreState } from '@/pocket/remote-store'
import { initialRemoteStoreState, selectRemoteRuntime, selectRemoteSessions } from '@/pocket/remote-store'

export const remoteStoreAtom = atom<RemoteStoreState>(initialRemoteStoreState)
export const remoteConnectionPhaseAtom = atom((get) => get(remoteStoreAtom).connection.phase)
export const remoteSessionsAtom = atom((get) => selectRemoteSessions(get(remoteStoreAtom)))
export const remoteCursorAtom = atom((get) => get(remoteStoreAtom).connection.cursor)
export const remoteServerInstanceIdAtom = atom((get) => get(remoteStoreAtom).connection.serverInstanceId)
export const remoteRuntimeAtomFamily = (sessionId: string) => atom((get) => selectRemoteRuntime(get(remoteStoreAtom), sessionId))
