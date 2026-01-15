import { ethers } from 'ethers';

export const web3Service = {
    connectWallet: async () => {
        if (typeof (window as any).ethereum === 'undefined') {
            throw new Error('Metamask is not installed');
        }

        const provider = new ethers.BrowserProvider((window as any).ethereum);
        const accounts = await provider.send("eth_requestAccounts", []);
        return accounts[0];
    },

    signMessage: async (message: string) => {
        if (typeof (window as any).ethereum === 'undefined') {
            throw new Error('Metamask is not installed');
        }

        const provider = new ethers.BrowserProvider((window as any).ethereum);
        const signer = await provider.getSigner();
        const signature = await signer.signMessage(message);
        return signature;
    },

    // Example function to interact with the Ticket Contract (Gateway)
    // In a real scenario, you would use the ABI and contract address
    getTicketContract: async (contractAddress: string, abi: any) => {
        const provider = new ethers.BrowserProvider((window as any).ethereum);
        const signer = await provider.getSigner();
        return new ethers.Contract(contractAddress, abi, signer);
    }
};
