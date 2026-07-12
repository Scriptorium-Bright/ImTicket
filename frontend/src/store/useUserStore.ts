import { create } from 'zustand'
import { web3Service } from '@/services/web3'
import { authApi, memberApi } from '@/services/api'

interface UserState {
    walletAddress: string | null
    nickname: string | null
    isLoggedIn: boolean
    isSignUpModalOpen: boolean
    connectWallet: () => Promise<void>
    logout: () => void
    setNickname: (name: string) => void
    setSignUpModalOpen: (isOpen: boolean) => void
}

export const useUserStore = create<UserState>((set, get) => ({
    walletAddress: null,
    nickname: null,
    isLoggedIn: false,
    isSignUpModalOpen: false,
    setSignUpModalOpen: (isOpen) => set({ isSignUpModalOpen: isOpen }),
    connectWallet: async () => {
        try {
            const address = await web3Service.connectWallet()

            try {
                await memberApi.validate(address)
            } catch (error) {
                console.log("User not found, triggering sign up")
                set({ walletAddress: address, isSignUpModalOpen: true })
                return
            }

            const nonceRes = await authApi.getNonce(address, 'LOGIN')
            const signature = await web3Service.signMessage(nonceRes.data.message)
            const tokenRes = await authApi.verifySignature(address, signature)
            localStorage.setItem('accessToken', tokenRes.data.token)
            const nicknameRes = await memberApi.getNickname(address)
            set({ walletAddress: address, isLoggedIn: true, nickname: nicknameRes.data })
        } catch (error) {
            console.error("Wallet connection failed:", error)
            alert("지갑 연결에 실패했습니다.")
        }
    },
    logout: () => set({ walletAddress: null, nickname: null, isLoggedIn: false }),
    setNickname: (name) => set({ nickname: name }),
}))
