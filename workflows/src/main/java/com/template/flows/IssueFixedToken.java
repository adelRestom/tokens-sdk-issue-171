package com.template.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.google.common.collect.ImmutableList;
import com.r3.corda.lib.tokens.contracts.states.FungibleToken;
import com.r3.corda.lib.tokens.contracts.types.IssuedTokenType;
import com.r3.corda.lib.tokens.contracts.types.TokenType;
import com.r3.corda.lib.tokens.contracts.utilities.AmountUtilitiesKt;
import com.r3.corda.lib.tokens.contracts.utilities.TransactionUtilitiesKt;
import com.r3.corda.lib.tokens.money.FiatCurrency;
import com.r3.corda.lib.tokens.workflows.flows.rpc.IssueTokens;
import com.r3.corda.lib.tokens.workflows.flows.rpc.MoveFungibleTokens;
import com.r3.corda.lib.tokens.workflows.types.PartyAndAmount;
import com.r3.corda.lib.tokens.workflows.utilities.QueryUtilitiesKt;
import com.template.states.MyTokenType;
import javafx.util.Pair;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.flows.FlowException;
import net.corda.core.flows.FlowLogic;
import net.corda.core.flows.StartableByRPC;
import net.corda.core.identity.Party;
import net.corda.core.node.ServiceHub;
import net.corda.core.node.services.Vault;
import net.corda.core.node.services.vault.PageSpecification;
import net.corda.core.node.services.vault.QueryCriteria;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.utilities.ProgressTracker;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static net.corda.core.node.services.vault.QueryCriteriaUtils.DEFAULT_PAGE_NUM;

@StartableByRPC
public class IssueFixedToken extends FlowLogic<SignedTransaction> {
    private final ProgressTracker progressTracker = new ProgressTracker();

    private final String currency;
    private final Long quantity;
    private final Party recipient;

    public IssueFixedToken(String currency, Long quantity, Party recipient) {
        this.currency = currency;
        this.quantity = quantity;
        this.recipient = recipient;
    }

    @Override
    public ProgressTracker getProgressTracker() {
        return progressTracker;
    }

    @Override
    @Suspendable
    public SignedTransaction call() throws FlowException {

        //get the fixed token type
        TokenType token = FiatCurrency.Companion.getInstance(currency);

        // Query criteria to fetch only the Shokens that belong to the Mint.
        QueryCriteria heldByMint = QueryUtilitiesKt.heldTokenAmountCriteria(token, getOurIdentity());

        // This will check the balance of the Mint.
        // This call should return <false, 0>: Meaning the current balance is 0 and it's not enough to
        // cover the required quantity.
        Pair<Boolean, BigDecimal> validationResultBeforeMinting = validateSourceBalance(heldByMint, quantity,
                getServiceHub());

        // Now we will mint tokens.
        subFlow(new MintFixedToken("USD", quantity));
        
        // Calling the exact same function with the exact same parameters.
        // Now it should return <true, quantity>: Meaning the current balance is "quantity" and it's enough to
        // cover the required quantity.
        Pair<Boolean, BigDecimal> validationResultAfterMinting = validateSourceBalance(heldByMint, quantity,
                getServiceHub());

        // Since we have enough balance, we will move the tokens to the recipient.
        Amount<TokenType> amount = AmountUtilitiesKt.amount(quantity, token);
        PartyAndAmount partyAndAmount = new PartyAndAmount(recipient, amount);
        return subFlow(new MoveFungibleTokens(Collections.singletonList(partyAndAmount),
                Collections.emptyList(),
                heldByMint, getOurIdentity()));
    }

    @Suspendable
    private Pair<Boolean, BigDecimal> validateSourceBalance(QueryCriteria heldBySource,
                                                            double requiredQuantity,
                                                            ServiceHub serviceHub) throws FlowException {
        int pageNumber = DEFAULT_PAGE_NUM;
        final int pageSize = 200;
        long totalResults;
        long totalBalance = 0;

        do {
            PageSpecification pageSpec = new PageSpecification(pageNumber, pageSize);
            Vault.Page<FungibleToken> results = serviceHub.getVaultService().queryBy(FungibleToken.class,
                    heldBySource, pageSpec);
            if (results == null)
                return new Pair<>(false, new BigDecimal(0));
            totalResults = results.getTotalStatesAvailable();
            if (totalResults == 0)
                return new Pair<>(false, new BigDecimal(0));
            List<StateAndRef<FungibleToken>> pageMyTokens = results.getStates();
            long pageBalance = pageMyTokens.stream()
                    .mapToLong(t -> t.getState().getData().getAmount().getQuantity()).sum();
            totalBalance += pageBalance;
            // Break the loop if we have enough balance.
            BigDecimal sourceBalance = new BigDecimal(totalBalance);
            if (sourceBalance.compareTo(new BigDecimal(requiredQuantity)) >= 0)
                // Please note that this is not the source balance; this is just the balance reached so far, which is
                // enough to cover the required quantity.
                return new Pair(true, sourceBalance);
            pageNumber++;
        }
        while ((pageSize * (pageNumber - 1) <= totalResults));

        BigDecimal sourceBalance = new BigDecimal(totalBalance);
        if (sourceBalance.compareTo(new BigDecimal(requiredQuantity)) < 0)
            return new Pair(false, sourceBalance);
        else
            return new Pair(true, sourceBalance);
    } 
}
