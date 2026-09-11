import { create } from 'zustand'
import { web3Service } from '@/services/web3'
import { authApi, memberApi } from '@/services/api'

interface UserState {
    walletAddress: string | null
    nickname: string | null
    isLoggedIn: boolean
    isConnecting: boolean
    isSignUpModalOpen: boolean
    connectWallet: () => Promise<void>
    loginWithWallet: (walletAddress: string) => Promise<void>
    logout: () => void
    setNickname: (name: string) => void
    setSignUpModalOpen: (isOpen: boolean) => void
}

export const useUserStore = create<UserState>((set, get) => ({
    walletAddress: null,
    nickname: null,
    isLoggedIn: false,
    isConnecting: false,
    isSignUpModalOpen: false,
    setSignUpModalOpen: (isOpen) => set({ isSignUpModalOpen: isOpen }),
    connectWallet: async () => {
        if (get().isConnecting) return

        set({ isConnecting: true })
        try {
            const address = await web3Service.connectWallet()

            try {
                await memberApi.validate(address)
            } catch (error) {
                console.log("User not found, triggering sign up")
                set({ walletAddress: address, isSignUpModalOpen: true })
                return
            }

            await get().loginWithWallet(address)
        } catch (error) {
            console.error("Wallet connection failed:", error)
            alert("지갑 연결에 실패했습니다.")
        } finally {
            set({ isConnecting: false })
        }
    },
    loginWithWallet: async (walletAddress) => {
        const nonceRes = await authApi.getNonce(walletAddress, 'LOGIN')
        const signature = await web3Service.signMessage(nonceRes.data.message)
        const tokenRes = await authApi.verifySignature(walletAddress, signature)
        localStorage.setItem('accessToken', tokenRes.data.token)
        const nicknameRes = await memberApi.getNickname(walletAddress)
        set({ walletAddress, isLoggedIn: true, nickname: nicknameRes.data })
    },
    logout: () => {
        localStorage.removeItem('accessToken')
        set({ walletAddress: null, nickname: null, isLoggedIn: false })
    },
    setNickname: (name) => set({ nickname: name }),
}))
