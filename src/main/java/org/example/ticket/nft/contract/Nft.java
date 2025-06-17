package org.example.ticket.nft.contract;

import io.reactivex.Flowable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.RemoteCall;
import org.web3j.protocol.core.RemoteFunctionCall;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.BaseEventResponse;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tuples.generated.Tuple2;
import org.web3j.tx.Contract;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.ContractGasProvider;

/**
 * <p>Auto generated code.
 * <p><strong>Do not modify!</strong>
 * <p>Please use the <a href="https://docs.web3j.io/command_line.html">web3j command line tools</a>,
 * or the org.web3j.codegen.SolidityFunctionWrapperGenerator in the 
 * <a href="https://github.com/web3j/web3j/tree/master/codegen">codegen module</a> to update.
 *
 * <p>Generated with web3j version 4.5.16.
 */
@SuppressWarnings("rawtypes")
public class Nft extends Contract {
    public static final String BINARY = "608060405234801561000f575f80fd5b506040518060400160405280600381526020017f4e465400000000000000000000000000000000000000000000000000000000008152506040518060400160405280600481526020017f4e46545300000000000000000000000000000000000000000000000000000000815250815f908161008a91906102dc565b50806001908161009a91906102dc565b5050506103ab565b5f81519050919050565b7f4e487b71000000000000000000000000000000000000000000000000000000005f52604160045260245ffd5b7f4e487b71000000000000000000000000000000000000000000000000000000005f52602260045260245ffd5b5f600282049050600182168061011d57607f821691505b6020821081036101305761012f6100d9565b5b50919050565b5f819050815f5260205f209050919050565b5f6020601f8301049050919050565b5f82821b905092915050565b5f600883026101927fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff82610157565b61019c8683610157565b95508019841693508086168417925050509392505050565b5f819050919050565b5f819050919050565b5f6101e06101db6101d6846101b4565b6101bd565b6101b4565b9050919050565b5f819050919050565b6101f9836101c6565b61020d610205826101e7565b848454610163565b825550505050565b5f90565b610221610215565b61022c8184846101f0565b505050565b5b8181101561024f576102445f82610219565b600181019050610232565b5050565b601f8211156102945761026581610136565b61026e84610148565b8101602085101561027d578190505b61029161028985610148565b830182610231565b50505b505050565b5f82821c905092915050565b5f6102b45f1984600802610299565b1980831691505092915050565b5f6102cc83836102a5565b9150826002028217905092915050565b6102e5826100a2565b67ffffffffffffffff8111156102fe576102fd6100ac565b5b6103088254610106565b610313828285610253565b5f60209050601f831160018114610344575f8415610332578287015190505b61033c85826102c1565b8655506103a3565b601f19841661035286610136565b5f5b8281101561037957848901518255600182019150602085019450602081019050610354565b868310156103965784890151610392601f8916826102a5565b8355505b6001600288020188555050505b505050505050565b6129e7806103b85f395ff3fe608060405234801561000f575f80fd5b506004361061011f575f3560e01c80636352211e116100ab578063b88d4fde1161006f578063b88d4fde1461033d578063c87b56dd14610359578063e985e9c514610389578063eacabe14146103b9578063f257f548146103e95761011f565b80636352211e146102735780636c8b703f146102a357806370a08231146102d357806395d89b4114610303578063a22cb465146103215761011f565b806318160ddd116100f257806318160ddd146101bd57806323b872dd146101db5780632f745c59146101f757806342842e0e146102275780634f6ccce7146102435761011f565b806301ffc9a71461012357806306fdde0314610153578063081812fc14610171578063095ea7b3146101a1575b5f80fd5b61013d60048036038101906101389190611ca7565b61041a565b60405161014a9190611cec565b60405180910390f35b61015b610493565b6040516101689190611d75565b60405180910390f35b61018b60048036038101906101869190611dc8565b610522565b6040516101989190611e32565b60405180910390f35b6101bb60048036038101906101b69190611e75565b61053d565b005b6101c5610553565b6040516101d29190611ec2565b60405180910390f35b6101f560048036038101906101f09190611edb565b61055f565b005b610211600480360381019061020c9190611e75565b61065e565b60405161021e9190611ec2565b60405180910390f35b610241600480360381019061023c9190611edb565b610702565b005b61025d60048036038101906102589190611dc8565b610721565b60405161026a9190611ec2565b60405180910390f35b61028d60048036038101906102889190611dc8565b610793565b60405161029a9190611e32565b60405180910390f35b6102bd60048036038101906102b89190611dc8565b6107a4565b6040516102ca9190611d75565b60405180910390f35b6102ed60048036038101906102e89190611f2b565b61083f565b6040516102fa9190611ec2565b60405180910390f35b61030b6108f5565b6040516103189190611d75565b60405180910390f35b61033b60048036038101906103369190611f80565b610985565b005b610357600480360381019061035291906120ea565b61099b565b005b610373600480360381019061036e9190611dc8565b6109c0565b6040516103809190611d75565b60405180910390f35b6103a3600480360381019061039e919061216a565b610a61565b6040516103b09190611cec565b60405180910390f35b6103d360048036038101906103ce9190612246565b610aef565b6040516103e09190611ec2565b60405180910390f35b61040360048036038101906103fe9190611f2b565b610b3a565b60405161041192919061245a565b60405180910390f35b5f7f780e9d63000000000000000000000000000000000000000000000000000000007bffffffffffffffffffffffffffffffffffffffffffffffffffffffff1916827bffffffffffffffffffffffffffffffffffffffffffffffffffffffff1916148061048c575061048b82610cb8565b5b9050919050565b60605f80546104a1906124bc565b80601f01602080910402602001604051908101604052809291908181526020018280546104cd906124bc565b80156105185780601f106104ef57610100808354040283529160200191610518565b820191905f5260205f20905b8154815290600101906020018083116104fb57829003601f168201915b5050505050905090565b5f61052c82610d99565b5061053682610e1f565b9050919050565b61054f828261054a610e58565b610e5f565b5050565b5f600880549050905090565b5f73ffffffffffffffffffffffffffffffffffffffff168273ffffffffffffffffffffffffffffffffffffffff16036105cf575f6040517f64a0ae920000000000000000000000000000000000000000000000000000000081526004016105c69190611e32565b60405180910390fd5b5f6105e283836105dd610e58565b610e71565b90508373ffffffffffffffffffffffffffffffffffffffff168173ffffffffffffffffffffffffffffffffffffffff1614610658578382826040517f64283d7b00000000000000000000000000000000000000000000000000000000815260040161064f939291906124ec565b60405180910390fd5b50505050565b5f6106688361083f565b82106106ad5782826040517fa57d13dc0000000000000000000000000000000000000000000000000000000081526004016106a4929190612521565b60405180910390fd5b60065f8473ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff1681526020019081526020015f205f8381526020019081526020015f2054905092915050565b61071c83838360405180602001604052805f81525061099b565b505050565b5f61072a610553565b821061076f575f826040517fa57d13dc000000000000000000000000000000000000000000000000000000008152600401610766929190612521565b60405180910390fd5b6008828154811061078357610782612548565b5b905f5260205f2001549050919050565b5f61079d82610d99565b9050919050565b600b602052805f5260405f205f9150905080546107c0906124bc565b80601f01602080910402602001604051908101604052809291908181526020018280546107ec906124bc565b80156108375780601f1061080e57610100808354040283529160200191610837565b820191905f5260205f20905b81548152906001019060200180831161081a57829003601f168201915b505050505081565b5f8073ffffffffffffffffffffffffffffffffffffffff168273ffffffffffffffffffffffffffffffffffffffff16036108b0575f6040517f89c62b640000000000000000000000000000000000000000000000000000000081526004016108a79190611e32565b60405180910390fd5b60035f8373ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff1681526020019081526020015f20549050919050565b606060018054610904906124bc565b80601f0160208091040260200160405190810160405280929190818152602001828054610930906124bc565b801561097b5780601f106109525761010080835404028352916020019161097b565b820191905f5260205f20905b81548152906001019060200180831161095e57829003601f168201915b5050505050905090565b610997610990610e58565b8383610f8b565b5050565b6109a684848461055f565b6109ba6109b1610e58565b858585856110f4565b50505050565b6060600b5f8381526020019081526020015f2080546109de906124bc565b80601f0160208091040260200160405190810160405280929190818152602001828054610a0a906124bc565b8015610a555780601f10610a2c57610100808354040283529160200191610a55565b820191905f5260205f20905b815481529060010190602001808311610a3857829003601f168201915b50505050509050919050565b5f60055f8473ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff1681526020019081526020015f205f8373ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff1681526020019081526020015f205f9054906101000a900460ff16905092915050565b5f610afa600a6112a0565b5f610b05600a6112b4565b905082600b5f8381526020019081526020015f209081610b259190612712565b50610b3084826112c0565b8091505092915050565b6060805f610b478461083f565b90505f8111610b8b576040517f08c379a0000000000000000000000000000000000000000000000000000000008152600401610b829061282b565b60405180910390fd5b5f8167ffffffffffffffff811115610ba657610ba5611fc6565b5b604051908082528060200260200182016040528015610bd45781602001602082028036833780820191505090505b5090505f8267ffffffffffffffff811115610bf257610bf1611fc6565b5b604051908082528060200260200182016040528015610c2557816020015b6060815260200190600190039081610c105790505b5090505f5b83811015610ca957610c3c878261065e565b838281518110610c4f57610c4e612548565b5b602002602001018181525050610c7e838281518110610c7157610c70612548565b5b60200260200101516109c0565b828281518110610c9157610c90612548565b5b60200260200101819052508080600101915050610c2a565b50818194509450505050915091565b5f7f80ac58cd000000000000000000000000000000000000000000000000000000007bffffffffffffffffffffffffffffffffffffffffffffffffffffffff1916827bffffffffffffffffffffffffffffffffffffffffffffffffffffffff19161480610d8257507f5b5e139f000000000000000000000000000000000000000000000000000000007bffffffffffffffffffffffffffffffffffffffffffffffffffffffff1916827bffffffffffffffffffffffffffffffffffffffffffffffffffffffff1916145b80610d925750610d91826112dd565b5b9050919050565b5f80610da483611346565b90505f73ffffffffffffffffffffffffffffffffffffffff168173ffffffffffffffffffffffffffffffffffffffff1603610e1657826040517f7e273289000000000000000000000000000000000000000000000000000000008152600401610e0d9190611ec2565b60405180910390fd5b80915050919050565b5f60045f8381526020019081526020015f205f9054906101000a900473ffffffffffffffffffffffffffffffffffffffff169050919050565b5f33905090565b610e6c838383600161137f565b505050565b5f80610e7e85858561153e565b90505f73ffffffffffffffffffffffffffffffffffffffff168173ffffffffffffffffffffffffffffffffffffffff1603610ec157610ebc84611749565b610f00565b8473ffffffffffffffffffffffffffffffffffffffff168173ffffffffffffffffffffffffffffffffffffffff1614610eff57610efe818561178d565b5b5b5f73ffffffffffffffffffffffffffffffffffffffff168573ffffffffffffffffffffffffffffffffffffffff1603610f4157610f3c84611864565b610f80565b8473ffffffffffffffffffffffffffffffffffffffff168173ffffffffffffffffffffffffffffffffffffffff1614610f7f57610f7e8585611924565b5b5b809150509392505050565b5f73ffffffffffffffffffffffffffffffffffffffff168273ffffffffffffffffffffffffffffffffffffffff1603610ffb57816040517f5b08ba18000000000000000000000000000000000000000000000000000000008152600401610ff29190611e32565b60405180910390fd5b8060055f8573ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff1681526020019081526020015f205f8473ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff1681526020019081526020015f205f6101000a81548160ff0219169083151502179055508173ffffffffffffffffffffffffffffffffffffffff168373ffffffffffffffffffffffffffffffffffffffff167f17307eab39ab6107e8899845ad3d59bd9653f200f220920489ca2b5937696c31836040516110e79190611cec565b60405180910390a3505050565b5f8373ffffffffffffffffffffffffffffffffffffffff163b1115611299578273ffffffffffffffffffffffffffffffffffffffff1663150b7a02868685856040518563ffffffff1660e01b8152600401611152949392919061289b565b6020604051808303815f875af192505050801561118d57506040513d601f19601f8201168201806040525081019061118a91906128f9565b60015b61120e573d805f81146111bb576040519150601f19603f3d011682016040523d82523d5f602084013e6111c0565b606091505b505f81510361120657836040517f64a0ae920000000000000000000000000000000000000000000000000000000081526004016111fd9190611e32565b60405180910390fd5b805181602001fd5b63150b7a0260e01b7bffffffffffffffffffffffffffffffffffffffffffffffffffffffff1916817bffffffffffffffffffffffffffffffffffffffffffffffffffffffff19161461129757836040517f64a0ae9200000000000000000000000000000000000000000000000000000000815260040161128e9190611e32565b60405180910390fd5b505b5050505050565b6001815f015f828254019250508190555050565b5f815f01549050919050565b6112d9828260405180602001604052805f8152506119a8565b5050565b5f7f01ffc9a7000000000000000000000000000000000000000000000000000000007bffffffffffffffffffffffffffffffffffffffffffffffffffffffff1916827bffffffffffffffffffffffffffffffffffffffffffffffffffffffff1916149050919050565b5f60025f8381526020019081526020015f205f9054906101000a900473ffffffffffffffffffffffffffffffffffffffff169050919050565b80806113b757505f73ffffffffffffffffffffffffffffffffffffffff168273ffffffffffffffffffffffffffffffffffffffff1614155b156114e9575f6113c684610d99565b90505f73ffffffffffffffffffffffffffffffffffffffff168373ffffffffffffffffffffffffffffffffffffffff161415801561143057508273ffffffffffffffffffffffffffffffffffffffff168173ffffffffffffffffffffffffffffffffffffffff1614155b801561144357506114418184610a61565b155b1561148557826040517fa9fbf51f00000000000000000000000000000000000000000000000000000000815260040161147c9190611e32565b60405180910390fd5b81156114e757838573ffffffffffffffffffffffffffffffffffffffff168273ffffffffffffffffffffffffffffffffffffffff167f8c5be1e5ebec7d5bd14f71427d1e84f3dd0314c0f7b2291e5b200ac8c7c3b92560405160405180910390a45b505b8360045f8581526020019081526020015f205f6101000a81548173ffffffffffffffffffffffffffffffffffffffff021916908373ffffffffffffffffffffffffffffffffffffffff16021790555050505050565b5f8061154984611346565b90505f73ffffffffffffffffffffffffffffffffffffffff168373ffffffffffffffffffffffffffffffffffffffff161461158a576115898184866119cb565b5b5f73ffffffffffffffffffffffffffffffffffffffff168173ffffffffffffffffffffffffffffffffffffffff1614611615576115c95f855f8061137f565b600160035f8373ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff1681526020019081526020015f205f82825403925050819055505b5f73ffffffffffffffffffffffffffffffffffffffff168573ffffffffffffffffffffffffffffffffffffffff161461169457600160035f8773ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff1681526020019081526020015f205f82825401925050819055505b8460025f8681526020019081526020015f205f6101000a81548173ffffffffffffffffffffffffffffffffffffffff021916908373ffffffffffffffffffffffffffffffffffffffff160217905550838573ffffffffffffffffffffffffffffffffffffffff168273ffffffffffffffffffffffffffffffffffffffff167fddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef60405160405180910390a4809150509392505050565b60088054905060095f8381526020019081526020015f2081905550600881908060018154018082558091505060019003905f5260205f20015f909190919091505550565b5f6117978361083f565b90505f60075f8481526020019081526020015f205490505f60065f8673ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff1681526020019081526020015f209050828214611836575f815f8581526020019081526020015f2054905080825f8581526020019081526020015f20819055508260075f8381526020019081526020015f2081905550505b60075f8581526020019081526020015f205f9055805f8481526020019081526020015f205f90555050505050565b5f60016008805490506118779190612951565b90505f60095f8481526020019081526020015f205490505f600883815481106118a3576118a2612548565b5b905f5260205f200154905080600883815481106118c3576118c2612548565b5b905f5260205f2001819055508160095f8381526020019081526020015f208190555060095f8581526020019081526020015f205f9055600880548061190b5761190a612984565b5b600190038181905f5260205f20015f9055905550505050565b5f60016119308461083f565b61193a9190612951565b90508160065f8573ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff1681526020019081526020015f205f8381526020019081526020015f20819055508060075f8481526020019081526020015f2081905550505050565b6119b28383611a8e565b6119c66119bd610e58565b5f8585856110f4565b505050565b6119d6838383611b81565b611a89575f73ffffffffffffffffffffffffffffffffffffffff168373ffffffffffffffffffffffffffffffffffffffff1603611a4a57806040517f7e273289000000000000000000000000000000000000000000000000000000008152600401611a419190611ec2565b60405180910390fd5b81816040517f177e802f000000000000000000000000000000000000000000000000000000008152600401611a80929190612521565b60405180910390fd5b505050565b5f73ffffffffffffffffffffffffffffffffffffffff168273ffffffffffffffffffffffffffffffffffffffff1603611afe575f6040517f64a0ae92000000000000000000000000000000000000000000000000000000008152600401611af59190611e32565b60405180910390fd5b5f611b0a83835f610e71565b90505f73ffffffffffffffffffffffffffffffffffffffff168173ffffffffffffffffffffffffffffffffffffffff1614611b7c575f6040517f73c6ac6e000000000000000000000000000000000000000000000000000000008152600401611b739190611e32565b60405180910390fd5b505050565b5f8073ffffffffffffffffffffffffffffffffffffffff168373ffffffffffffffffffffffffffffffffffffffff1614158015611c3857508273ffffffffffffffffffffffffffffffffffffffff168473ffffffffffffffffffffffffffffffffffffffff161480611bf95750611bf88484610a61565b5b80611c3757508273ffffffffffffffffffffffffffffffffffffffff16611c1f83610e1f565b73ffffffffffffffffffffffffffffffffffffffff16145b5b90509392505050565b5f604051905090565b5f80fd5b5f80fd5b5f7fffffffff0000000000000000000000000000000000000000000000000000000082169050919050565b611c8681611c52565b8114611c90575f80fd5b50565b5f81359050611ca181611c7d565b92915050565b5f60208284031215611cbc57611cbb611c4a565b5b5f611cc984828501611c93565b91505092915050565b5f8115159050919050565b611ce681611cd2565b82525050565b5f602082019050611cff5f830184611cdd565b92915050565b5f81519050919050565b5f82825260208201905092915050565b8281835e5f83830152505050565b5f601f19601f8301169050919050565b5f611d4782611d05565b611d518185611d0f565b9350611d61818560208601611d1f565b611d6a81611d2d565b840191505092915050565b5f6020820190508181035f830152611d8d8184611d3d565b905092915050565b5f819050919050565b611da781611d95565b8114611db1575f80fd5b50565b5f81359050611dc281611d9e565b92915050565b5f60208284031215611ddd57611ddc611c4a565b5b5f611dea84828501611db4565b91505092915050565b5f73ffffffffffffffffffffffffffffffffffffffff82169050919050565b5f611e1c82611df3565b9050919050565b611e2c81611e12565b82525050565b5f602082019050611e455f830184611e23565b92915050565b611e5481611e12565b8114611e5e575f80fd5b50565b5f81359050611e6f81611e4b565b92915050565b5f8060408385031215611e8b57611e8a611c4a565b5b5f611e9885828601611e61565b9250506020611ea985828601611db4565b9150509250929050565b611ebc81611d95565b82525050565b5f602082019050611ed55f830184611eb3565b92915050565b5f805f60608486031215611ef257611ef1611c4a565b5b5f611eff86828701611e61565b9350506020611f1086828701611e61565b9250506040611f2186828701611db4565b9150509250925092565b5f60208284031215611f4057611f3f611c4a565b5b5f611f4d84828501611e61565b91505092915050565b611f5f81611cd2565b8114611f69575f80fd5b50565b5f81359050611f7a81611f56565b92915050565b5f8060408385031215611f9657611f95611c4a565b5b5f611fa385828601611e61565b9250506020611fb485828601611f6c565b9150509250929050565b5f80fd5b5f80fd5b7f4e487b71000000000000000000000000000000000000000000000000000000005f52604160045260245ffd5b611ffc82611d2d565b810181811067ffffffffffffffff8211171561201b5761201a611fc6565b5b80604052505050565b5f61202d611c41565b90506120398282611ff3565b919050565b5f67ffffffffffffffff82111561205857612057611fc6565b5b61206182611d2d565b9050602081019050919050565b828183375f83830152505050565b5f61208e6120898461203e565b612024565b9050828152602081018484840111156120aa576120a9611fc2565b5b6120b584828561206e565b509392505050565b5f82601f8301126120d1576120d0611fbe565b5b81356120e184826020860161207c565b91505092915050565b5f805f806080858703121561210257612101611c4a565b5b5f61210f87828801611e61565b945050602061212087828801611e61565b935050604061213187828801611db4565b925050606085013567ffffffffffffffff81111561215257612151611c4e565b5b61215e878288016120bd565b91505092959194509250565b5f80604083850312156121805761217f611c4a565b5b5f61218d85828601611e61565b925050602061219e85828601611e61565b9150509250929050565b5f67ffffffffffffffff8211156121c2576121c1611fc6565b5b6121cb82611d2d565b9050602081019050919050565b5f6121ea6121e5846121a8565b612024565b90508281526020810184848401111561220657612205611fc2565b5b61221184828561206e565b509392505050565b5f82601f83011261222d5761222c611fbe565b5b813561223d8482602086016121d8565b91505092915050565b5f806040838503121561225c5761225b611c4a565b5b5f61226985828601611e61565b925050602083013567ffffffffffffffff81111561228a57612289611c4e565b5b61229685828601612219565b9150509250929050565b5f81519050919050565b5f82825260208201905092915050565b5f819050602082019050919050565b6122d281611d95565b82525050565b5f6122e383836122c9565b60208301905092915050565b5f602082019050919050565b5f612305826122a0565b61230f81856122aa565b935061231a836122ba565b805f5b8381101561234a57815161233188826122d8565b975061233c836122ef565b92505060018101905061231d565b5085935050505092915050565b5f81519050919050565b5f82825260208201905092915050565b5f819050602082019050919050565b5f82825260208201905092915050565b5f61239a82611d05565b6123a48185612380565b93506123b4818560208601611d1f565b6123bd81611d2d565b840191505092915050565b5f6123d38383612390565b905092915050565b5f602082019050919050565b5f6123f182612357565b6123fb8185612361565b93508360208202850161240d85612371565b805f5b85811015612448578484038952815161242985826123c8565b9450612434836123db565b925060208a01995050600181019050612410565b50829750879550505050505092915050565b5f6040820190508181035f83015261247281856122fb565b9050818103602083015261248681846123e7565b90509392505050565b7f4e487b71000000000000000000000000000000000000000000000000000000005f52602260045260245ffd5b5f60028204905060018216806124d357607f821691505b6020821081036124e6576124e561248f565b5b50919050565b5f6060820190506124ff5f830186611e23565b61250c6020830185611eb3565b6125196040830184611e23565b949350505050565b5f6040820190506125345f830185611e23565b6125416020830184611eb3565b9392505050565b7f4e487b71000000000000000000000000000000000000000000000000000000005f52603260045260245ffd5b5f819050815f5260205f209050919050565b5f6020601f8301049050919050565b5f82821b905092915050565b5f600883026125d17fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff82612596565b6125db8683612596565b95508019841693508086168417925050509392505050565b5f819050919050565b5f61261661261161260c84611d95565b6125f3565b611d95565b9050919050565b5f819050919050565b61262f836125fc565b61264361263b8261261d565b8484546125a2565b825550505050565b5f90565b61265761264b565b612662818484612626565b505050565b5b818110156126855761267a5f8261264f565b600181019050612668565b5050565b601f8211156126ca5761269b81612575565b6126a484612587565b810160208510156126b3578190505b6126c76126bf85612587565b830182612667565b50505b505050565b5f82821c905092915050565b5f6126ea5f19846008026126cf565b1980831691505092915050565b5f61270283836126db565b9150826002028217905092915050565b61271b82611d05565b67ffffffffffffffff81111561273457612733611fc6565b5b61273e82546124bc565b612749828285612689565b5f60209050601f83116001811461277a575f8415612768578287015190505b61277285826126f7565b8655506127d9565b601f19841661278886612575565b5f5b828110156127af5784890151825560018201915060208501945060208101905061278a565b868310156127cc57848901516127c8601f8916826126db565b8355505b6001600288020188555050505b505050505050565b7f4e4654206e6f7420666f756e642e0000000000000000000000000000000000005f82015250565b5f612815600e83611d0f565b9150612820826127e1565b602082019050919050565b5f6020820190508181035f83015261284281612809565b9050919050565b5f81519050919050565b5f82825260208201905092915050565b5f61286d82612849565b6128778185612853565b9350612887818560208601611d1f565b61289081611d2d565b840191505092915050565b5f6080820190506128ae5f830187611e23565b6128bb6020830186611e23565b6128c86040830185611eb3565b81810360608301526128da8184612863565b905095945050505050565b5f815190506128f381611c7d565b92915050565b5f6020828403121561290e5761290d611c4a565b5b5f61291b848285016128e5565b91505092915050565b7f4e487b71000000000000000000000000000000000000000000000000000000005f52601160045260245ffd5b5f61295b82611d95565b915061296683611d95565b925082820390508181111561297e5761297d612924565b5b92915050565b7f4e487b71000000000000000000000000000000000000000000000000000000005f52603160045260245ffdfea2646970667358221220ba81d40aba1468fa490526c0ef272b28d83ff4337b85fa6c4f19ff509c3c5afd64736f6c634300081a0033";

    public static final String FUNC_APPROVE = "approve";

    public static final String FUNC_MINTNFT = "mintNFT";

    public static final String FUNC_safeTransferFrom = "safeTransferFrom";

    public static final String FUNC_SETAPPROVALFORALL = "setApprovalForAll";

    public static final String FUNC_TRANSFERFROM = "transferFrom";

    public static final String FUNC_BALANCEOF = "balanceOf";

    public static final String FUNC_GETAPPROVED = "getApproved";

    public static final String FUNC_GETNFTTOKENS = "getNftTokens";

    public static final String FUNC_ISAPPROVEDFORALL = "isApprovedForAll";

    public static final String FUNC_NAME = "name";

    public static final String FUNC_OWNEROF = "ownerOf";

    public static final String FUNC_SUPPORTSINTERFACE = "supportsInterface";

    public static final String FUNC_SYMBOL = "symbol";

    public static final String FUNC_TOKENBYINDEX = "tokenByIndex";

    public static final String FUNC_TOKENOFOWNERBYINDEX = "tokenOfOwnerByIndex";

    public static final String FUNC_TOKENURI = "tokenURI";

    public static final String FUNC_TOKENURIS = "tokenURIs";

    public static final String FUNC_TOTALSUPPLY = "totalSupply";

    public static final Event APPROVAL_EVENT = new Event("Approval", 
            Arrays.<TypeReference<?>>asList(new TypeReference<Address>(true) {}, new TypeReference<Address>(true) {}, new TypeReference<Uint256>(true) {}));
    ;

    public static final Event APPROVALFORALL_EVENT = new Event("ApprovalForAll", 
            Arrays.<TypeReference<?>>asList(new TypeReference<Address>(true) {}, new TypeReference<Address>(true) {}, new TypeReference<Bool>() {}));
    ;

    public static final Event TRANSFER_EVENT = new Event("Transfer", 
            Arrays.<TypeReference<?>>asList(new TypeReference<Address>(true) {}, new TypeReference<Address>(true) {}, new TypeReference<Uint256>(true) {}));
    ;

    @Deprecated
    protected Nft(String contractAddress, Web3j web3j, Credentials credentials, BigInteger gasPrice, BigInteger gasLimit) {
        super(BINARY, contractAddress, web3j, credentials, gasPrice, gasLimit);
    }

    protected Nft(String contractAddress, Web3j web3j, Credentials credentials, ContractGasProvider contractGasProvider) {
        super(BINARY, contractAddress, web3j, credentials, contractGasProvider);
    }

    @Deprecated
    protected Nft(String contractAddress, Web3j web3j, TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
        super(BINARY, contractAddress, web3j, transactionManager, gasPrice, gasLimit);
    }

    protected Nft(String contractAddress, Web3j web3j, TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        super(BINARY, contractAddress, web3j, transactionManager, contractGasProvider);
    }

    public RemoteFunctionCall<TransactionReceipt> approve(String to, BigInteger tokenId) {
        final Function function = new Function(
                FUNC_APPROVE, 
                Arrays.<Type>asList(new Address(160, to),
                new Uint256(tokenId)),
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public List<ApprovalEventResponse> getApprovalEvents(TransactionReceipt transactionReceipt) {
        List<EventValuesWithLog> valueList = extractEventParametersWithLog(APPROVAL_EVENT, transactionReceipt);
        ArrayList<ApprovalEventResponse> responses = new ArrayList<ApprovalEventResponse>(valueList.size());
        for (EventValuesWithLog eventValues : valueList) {
            ApprovalEventResponse typedResponse = new ApprovalEventResponse();
            typedResponse.log = eventValues.getLog();
            typedResponse.owner = (String) eventValues.getIndexedValues().get(0).getValue();
            typedResponse.approved = (String) eventValues.getIndexedValues().get(1).getValue();
            typedResponse.tokenId = (BigInteger) eventValues.getIndexedValues().get(2).getValue();
            responses.add(typedResponse);
        }
        return responses;
    }

    public Flowable<ApprovalEventResponse> approvalEventFlowable(EthFilter filter) {
        return web3j.ethLogFlowable(filter).map(new io.reactivex.functions.Function<Log, ApprovalEventResponse>() {
            @Override
            public ApprovalEventResponse apply(Log log) {
                EventValuesWithLog eventValues = extractEventParametersWithLog(APPROVAL_EVENT, log);
                ApprovalEventResponse typedResponse = new ApprovalEventResponse();
                typedResponse.log = log;
                typedResponse.owner = (String) eventValues.getIndexedValues().get(0).getValue();
                typedResponse.approved = (String) eventValues.getIndexedValues().get(1).getValue();
                typedResponse.tokenId = (BigInteger) eventValues.getIndexedValues().get(2).getValue();
                return typedResponse;
            }
        });
    }

    public Flowable<ApprovalEventResponse> approvalEventFlowable(DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(APPROVAL_EVENT));
        return approvalEventFlowable(filter);
    }

    public List<ApprovalForAllEventResponse> getApprovalForAllEvents(TransactionReceipt transactionReceipt) {
        List<EventValuesWithLog> valueList = extractEventParametersWithLog(APPROVALFORALL_EVENT, transactionReceipt);
        ArrayList<ApprovalForAllEventResponse> responses = new ArrayList<ApprovalForAllEventResponse>(valueList.size());
        for (EventValuesWithLog eventValues : valueList) {
            ApprovalForAllEventResponse typedResponse = new ApprovalForAllEventResponse();
            typedResponse.log = eventValues.getLog();
            typedResponse.owner = (String) eventValues.getIndexedValues().get(0).getValue();
            typedResponse.operator = (String) eventValues.getIndexedValues().get(1).getValue();
            typedResponse.approved = (Boolean) eventValues.getNonIndexedValues().get(0).getValue();
            responses.add(typedResponse);
        }
        return responses;
    }

    public Flowable<ApprovalForAllEventResponse> approvalForAllEventFlowable(EthFilter filter) {
        return web3j.ethLogFlowable(filter).map(new io.reactivex.functions.Function<Log, ApprovalForAllEventResponse>() {
            @Override
            public ApprovalForAllEventResponse apply(Log log) {
                EventValuesWithLog eventValues = extractEventParametersWithLog(APPROVALFORALL_EVENT, log);
                ApprovalForAllEventResponse typedResponse = new ApprovalForAllEventResponse();
                typedResponse.log = log;
                typedResponse.owner = (String) eventValues.getIndexedValues().get(0).getValue();
                typedResponse.operator = (String) eventValues.getIndexedValues().get(1).getValue();
                typedResponse.approved = (Boolean) eventValues.getNonIndexedValues().get(0).getValue();
                return typedResponse;
            }
        });
    }

    public Flowable<ApprovalForAllEventResponse> approvalForAllEventFlowable(DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(APPROVALFORALL_EVENT));
        return approvalForAllEventFlowable(filter);
    }

    public RemoteFunctionCall<TransactionReceipt> mintNFT(String to, String _tokenURI) {
        final Function function = new Function(
                FUNC_MINTNFT, 
                Arrays.<Type>asList(new Address(160, to),
                new Utf8String(_tokenURI)),
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<TransactionReceipt> safeTransferFrom(String from, String to, BigInteger tokenId) {
        final Function function = new Function(
                FUNC_safeTransferFrom, 
                Arrays.<Type>asList(new Address(160, from),
                new Address(160, to),
                new Uint256(tokenId)),
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<TransactionReceipt> safeTransferFrom(String from, String to, BigInteger tokenId, byte[] data) {
        final Function function = new Function(
                FUNC_safeTransferFrom, 
                Arrays.<Type>asList(new Address(160, from),
                new Address(160, to),
                new Uint256(tokenId),
                new org.web3j.abi.datatypes.DynamicBytes(data)), 
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<TransactionReceipt> setApprovalForAll(String operator, Boolean approved) {
        final Function function = new Function(
                FUNC_SETAPPROVALFORALL, 
                Arrays.<Type>asList(new Address(160, operator),
                new Bool(approved)),
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public List<TransferEventResponse> getTransferEvents(TransactionReceipt transactionReceipt) {
        List<EventValuesWithLog> valueList = extractEventParametersWithLog(TRANSFER_EVENT, transactionReceipt);
        ArrayList<TransferEventResponse> responses = new ArrayList<TransferEventResponse>(valueList.size());
        for (EventValuesWithLog eventValues : valueList) {
            TransferEventResponse typedResponse = new TransferEventResponse();
            typedResponse.log = eventValues.getLog();
            typedResponse.from = (String) eventValues.getIndexedValues().get(0).getValue();
            typedResponse.to = (String) eventValues.getIndexedValues().get(1).getValue();
            typedResponse.tokenId = (BigInteger) eventValues.getIndexedValues().get(2).getValue();
            responses.add(typedResponse);
        }
        return responses;
    }

    public Flowable<TransferEventResponse> transferEventFlowable(EthFilter filter) {
        return web3j.ethLogFlowable(filter).map(new io.reactivex.functions.Function<Log, TransferEventResponse>() {
            @Override
            public TransferEventResponse apply(Log log) {
                EventValuesWithLog eventValues = extractEventParametersWithLog(TRANSFER_EVENT, log);
                TransferEventResponse typedResponse = new TransferEventResponse();
                typedResponse.log = log;
                typedResponse.from = (String) eventValues.getIndexedValues().get(0).getValue();
                typedResponse.to = (String) eventValues.getIndexedValues().get(1).getValue();
                typedResponse.tokenId = (BigInteger) eventValues.getIndexedValues().get(2).getValue();
                return typedResponse;
            }
        });
    }

    public Flowable<TransferEventResponse> transferEventFlowable(DefaultBlockParameter startBlock, DefaultBlockParameter endBlock) {
        EthFilter filter = new EthFilter(startBlock, endBlock, getContractAddress());
        filter.addSingleTopic(EventEncoder.encode(TRANSFER_EVENT));
        return transferEventFlowable(filter);
    }

    public RemoteFunctionCall<TransactionReceipt> transferFrom(String from, String to, BigInteger tokenId) {
        final Function function = new Function(
                FUNC_TRANSFERFROM, 
                Arrays.<Type>asList(new Address(160, from),
                new Address(160, to),
                new Uint256(tokenId)),
                Collections.<TypeReference<?>>emptyList());
        return executeRemoteCallTransaction(function);
    }

    public RemoteFunctionCall<BigInteger> balanceOf(String owner) {
        final Function function = new Function(FUNC_BALANCEOF, 
                Arrays.<Type>asList(new Address(160, owner)),
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint256>() {}));
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    public RemoteFunctionCall<String> getApproved(BigInteger tokenId) {
        final Function function = new Function(FUNC_GETAPPROVED, 
                Arrays.<Type>asList(new Uint256(tokenId)),
                Arrays.<TypeReference<?>>asList(new TypeReference<Address>() {}));
        return executeRemoteCallSingleValueReturn(function, String.class);
    }

    public RemoteFunctionCall<Tuple2<List<BigInteger>, List<String>>> getNftTokens(String _nftTokenOwner) {
        final Function function = new Function(FUNC_GETNFTTOKENS, 
                Arrays.<Type>asList(new Address(160, _nftTokenOwner)),
                Arrays.<TypeReference<?>>asList(new TypeReference<DynamicArray<Uint256>>() {}, new TypeReference<DynamicArray<Utf8String>>() {}));
        return new RemoteFunctionCall<Tuple2<List<BigInteger>, List<String>>>(function,
                new Callable<Tuple2<List<BigInteger>, List<String>>>() {
                    @Override
                    public Tuple2<List<BigInteger>, List<String>> call() throws Exception {
                        List<Type> results = executeCallMultipleValueReturn(function);
                        return new Tuple2<List<BigInteger>, List<String>>(
                                convertToNative((List<Uint256>) results.get(0).getValue()), 
                                convertToNative((List<Utf8String>) results.get(1).getValue()));
                    }
                });
    }

    public RemoteFunctionCall<Boolean> isApprovedForAll(String owner, String operator) {
        final Function function = new Function(FUNC_ISAPPROVEDFORALL, 
                Arrays.<Type>asList(new Address(160, owner),
                new Address(160, operator)),
                Arrays.<TypeReference<?>>asList(new TypeReference<Bool>() {}));
        return executeRemoteCallSingleValueReturn(function, Boolean.class);
    }

    public RemoteFunctionCall<String> name() {
        final Function function = new Function(FUNC_NAME, 
                Arrays.<Type>asList(), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Utf8String>() {}));
        return executeRemoteCallSingleValueReturn(function, String.class);
    }

    public RemoteFunctionCall<String> ownerOf(BigInteger tokenId) {
        final Function function = new Function(FUNC_OWNEROF, 
                Arrays.<Type>asList(new Uint256(tokenId)),
                Arrays.<TypeReference<?>>asList(new TypeReference<Address>() {}));
        return executeRemoteCallSingleValueReturn(function, String.class);
    }

    public RemoteFunctionCall<Boolean> supportsInterface(byte[] interfaceId) {
        final Function function = new Function(FUNC_SUPPORTSINTERFACE, 
                Arrays.<Type>asList(new org.web3j.abi.datatypes.generated.Bytes4(interfaceId)), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Bool>() {}));
        return executeRemoteCallSingleValueReturn(function, Boolean.class);
    }

    public RemoteFunctionCall<String> symbol() {
        final Function function = new Function(FUNC_SYMBOL, 
                Arrays.<Type>asList(), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Utf8String>() {}));
        return executeRemoteCallSingleValueReturn(function, String.class);
    }

    public RemoteFunctionCall<BigInteger> tokenByIndex(BigInteger index) {
        final Function function = new Function(FUNC_TOKENBYINDEX, 
                Arrays.<Type>asList(new Uint256(index)),
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint256>() {}));
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    public RemoteFunctionCall<BigInteger> tokenOfOwnerByIndex(String owner, BigInteger index) {
        final Function function = new Function(FUNC_TOKENOFOWNERBYINDEX, 
                Arrays.<Type>asList(new Address(160, owner),
                new Uint256(index)),
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint256>() {}));
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    public RemoteFunctionCall<String> tokenURI(BigInteger _tokenId) {
        final Function function = new Function(FUNC_TOKENURI, 
                Arrays.<Type>asList(new Uint256(_tokenId)),
                Arrays.<TypeReference<?>>asList(new TypeReference<Utf8String>() {}));
        return executeRemoteCallSingleValueReturn(function, String.class);
    }

    public RemoteFunctionCall<String> tokenURIs(BigInteger param0) {
        final Function function = new Function(FUNC_TOKENURIS, 
                Arrays.<Type>asList(new Uint256(param0)),
                Arrays.<TypeReference<?>>asList(new TypeReference<Utf8String>() {}));
        return executeRemoteCallSingleValueReturn(function, String.class);
    }

    public RemoteFunctionCall<BigInteger> totalSupply() {
        final Function function = new Function(FUNC_TOTALSUPPLY, 
                Arrays.<Type>asList(), 
                Arrays.<TypeReference<?>>asList(new TypeReference<Uint256>() {}));
        return executeRemoteCallSingleValueReturn(function, BigInteger.class);
    }

    @Deprecated
    public static Nft load(String contractAddress, Web3j web3j, Credentials credentials, BigInteger gasPrice, BigInteger gasLimit) {
        return new Nft(contractAddress, web3j, credentials, gasPrice, gasLimit);
    }

    @Deprecated
    public static Nft load(String contractAddress, Web3j web3j, TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
        return new Nft(contractAddress, web3j, transactionManager, gasPrice, gasLimit);
    }

    public static Nft load(String contractAddress, Web3j web3j, Credentials credentials, ContractGasProvider contractGasProvider) {
        return new Nft(contractAddress, web3j, credentials, contractGasProvider);
    }

    public static Nft load(String contractAddress, Web3j web3j, TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        return new Nft(contractAddress, web3j, transactionManager, contractGasProvider);
    }

    public static RemoteCall<Nft> deploy(Web3j web3j, Credentials credentials, ContractGasProvider contractGasProvider) {
        return deployRemoteCall(Nft.class, web3j, credentials, contractGasProvider, BINARY, "");
    }

    public static RemoteCall<Nft> deploy(Web3j web3j, TransactionManager transactionManager, ContractGasProvider contractGasProvider) {
        return deployRemoteCall(Nft.class, web3j, transactionManager, contractGasProvider, BINARY, "");
    }

    @Deprecated
    public static RemoteCall<Nft> deploy(Web3j web3j, Credentials credentials, BigInteger gasPrice, BigInteger gasLimit) {
        return deployRemoteCall(Nft.class, web3j, credentials, gasPrice, gasLimit, BINARY, "");
    }

    @Deprecated
    public static RemoteCall<Nft> deploy(Web3j web3j, TransactionManager transactionManager, BigInteger gasPrice, BigInteger gasLimit) {
        return deployRemoteCall(Nft.class, web3j, transactionManager, gasPrice, gasLimit, BINARY, "");
    }

    public static class ApprovalEventResponse extends BaseEventResponse {
        public String owner;

        public String approved;

        public BigInteger tokenId;
    }

    public static class ApprovalForAllEventResponse extends BaseEventResponse {
        public String owner;

        public String operator;

        public Boolean approved;
    }

    public static class TransferEventResponse extends BaseEventResponse {
        public String from;

        public String to;

        public BigInteger tokenId;
    }
}
