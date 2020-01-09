package com.template;

import com.google.common.collect.ImmutableList;
import com.r3.corda.lib.tokens.contracts.states.FungibleToken;
import com.r3.corda.lib.tokens.contracts.types.TokenType;
import com.r3.corda.lib.tokens.contracts.utilities.AmountUtilitiesKt;
import com.r3.corda.lib.tokens.money.FiatCurrency;
import com.template.flows.IssueFixedToken;
import com.template.flows.MintFixedToken;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.identity.Party;
import net.corda.core.node.NetworkParameters;
import net.corda.testing.node.MockNetwork;
import net.corda.testing.node.MockNetworkParameters;
import net.corda.testing.node.StartedMockNode;
import net.corda.testing.node.TestCordapp;
import org.junit.Assert;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class FlowTests {
    private MockNetwork network ;
    private StartedMockNode walletNode;
    private StartedMockNode mintNode;
    private Party walletParty;
    private Party mintParty;

    @BeforeAll
    public void setup() {
        network = new MockNetwork(new MockNetworkParameters().withCordappsForAllNodes(ImmutableList.of(
                TestCordapp.findCordapp("com.template.flows"),
                TestCordapp.findCordapp("com.r3.corda.lib.tokens.contracts"),
                TestCordapp.findCordapp("com.r3.corda.lib.tokens.workflows"),
                TestCordapp.findCordapp("com.r3.corda.lib.tokens.money"),
                TestCordapp.findCordapp("com.r3.corda.lib.tokens.selection")
                ))
                // Below values are taken from Tokens SDK ParameterUtilities.testNetworkParameters.
                .withNetworkParameters(new NetworkParameters(4, Collections.EMPTY_LIST,
                        10485760, 10485760*50,
                        Instant.now(), 1, Collections.EMPTY_MAP, Duration.ofDays(30), Collections.EMPTY_MAP)));
        mintNode = network.createPartyNode(
                CordaX500Name.parse("O=Mint,L=London,C=GB"));
        walletNode = network.createPartyNode(
                CordaX500Name.parse("O=Wallet,L=London,C=GB"));
        mintParty = mintNode.getInfo().getLegalIdentities().get(0);
        walletParty = walletNode.getInfo().getLegalIdentities().get(0);

        network.runNetwork();
    }

    @AfterAll
    public void tearDown() {
        network.stopNodes();
    }

    @Test
    @DisplayName("Must be able to mint tokens.")
    public void mintTest() {
        // We will issue 100 tokens to Wallet party.
        // The Mint will first mint 100 tokens, then move them to Wallet.
        MintFixedToken flow = new MintFixedToken("USD", 100L);
        mintNode.startFlow(flow);
        network.runNetwork();

        // Mint balance must be updated.
        List<StateAndRef<FungibleToken>> mintVaultTokens = mintNode.getServices().getVaultService()
                .queryBy(FungibleToken.class).getStates();
        long mintVaultBalance = mintVaultTokens.stream()
                .mapToLong(t -> t.getState().getData().getAmount().getQuantity()).sum();
        TokenType token = FiatCurrency.Companion.getInstance("USD");


        Assert.assertEquals(10000, mintVaultBalance);
    }

    @Test
    @DisplayName("Must be able to mint tokens then issue them.")
    public void issueTest() {
        // We will issue 100 tokens to Wallet party.
        // The Mint will first mint 100 tokens, then move them to Wallet.
        IssueFixedToken flow = new IssueFixedToken("USD", 10000L, walletParty);
        mintNode.startFlow(flow);
        network.runNetwork();

        // Wallet balance must be updated.
        List<StateAndRef<FungibleToken>> walletVaultTokens = walletNode.getServices().getVaultService()
                .queryBy(FungibleToken.class).getStates();
        long walletVaultBalance = walletVaultTokens.stream()
                .mapToLong(t -> t.getState().getData().getAmount().getQuantity()).sum();
        TokenType token = FiatCurrency.Companion.getInstance("USD");

        Assert.assertEquals(10000, walletVaultBalance);
    }
}
