package com.kb.tangtang.account.client.sync;

import com.kb.tangtang.account.client.sync.dto.BankAccountSyncDto;
import com.kb.tangtang.account.client.sync.dto.BankTransactionSyncDto;
import com.kb.tangtang.account.client.sync.dto.CardApprovalSyncDto;
import com.kb.tangtang.account.client.sync.dto.CardBillSyncDto;
import com.kb.tangtang.account.client.sync.dto.CardSyncDto;
import com.kb.tangtang.account.client.sync.dto.DepositSyncDto;
import com.kb.tangtang.account.client.sync.dto.DepositTransactionSyncDto;
import com.kb.tangtang.account.client.sync.dto.LoanSyncDto;
import com.kb.tangtang.account.client.sync.dto.LoanTransactionSyncDto;
import com.kb.tangtang.account.client.sync.dto.PayMoneySyncDto;
import com.kb.tangtang.account.client.sync.dto.PayMoneyTransactionSyncDto;
import com.kb.tangtang.account.client.sync.dto.SecuritiesTransactionSyncDto;
import com.kb.tangtang.account.client.sync.dto.StockAssetSyncDto;

import java.util.List;

/**
 * 키 접두사로 목서버/실 CODEF 를 가른다. `codef:` 로 시작하면 CODEF, 아니면 목서버 시나리오 키다.
 * 키를 만드는 쪽은 {@link SourceAwareScenarioKeyProvider} 다.
 */
public class RoutingFinancialSyncClient implements FinancialSyncClient {

    private final FinancialSyncClient mock;
    private final FinancialSyncClient codef;

    public RoutingFinancialSyncClient(FinancialSyncClient mock, FinancialSyncClient codef) {
        this.mock = mock;
        this.codef = codef;
    }

    private FinancialSyncClient route(String key) {
        return CodefFinancialSyncClient.isCodefKey(key) ? codef : mock;
    }

    @Override
    public List<BankAccountSyncDto> getBankAccounts(String key) {
        return route(key).getBankAccounts(key);
    }

    @Override
    public List<BankTransactionSyncDto> getBankTransactions(String key, long accountId) {
        return route(key).getBankTransactions(key, accountId);
    }

    @Override
    public List<BankTransactionSyncDto> getBankTransactions(String key, long accountId, String yearMonth) {
        return route(key).getBankTransactions(key, accountId, yearMonth);
    }

    @Override
    public List<DepositSyncDto> getDeposits(String key) {
        return route(key).getDeposits(key);
    }

    @Override
    public List<DepositTransactionSyncDto> getDepositTransactions(String key, long depositAccountId) {
        return route(key).getDepositTransactions(key, depositAccountId);
    }

    @Override
    public StockAssetSyncDto getStockAsset(String key) {
        return route(key).getStockAsset(key);
    }

    @Override
    public List<SecuritiesTransactionSyncDto> getSecuritiesTransactions(String key, long accountId) {
        return route(key).getSecuritiesTransactions(key, accountId);
    }

    @Override
    public List<LoanSyncDto> getLoans(String key) {
        return route(key).getLoans(key);
    }

    @Override
    public List<LoanTransactionSyncDto> getLoanTransactions(String key, long loanId) {
        return route(key).getLoanTransactions(key, loanId);
    }

    @Override
    public List<PayMoneySyncDto> getPayMoney(String key) {
        return route(key).getPayMoney(key);
    }

    @Override
    public List<PayMoneyTransactionSyncDto> getPayMoneyTransactions(String key, long payMoneyId) {
        return route(key).getPayMoneyTransactions(key, payMoneyId);
    }

    @Override
    public List<CardSyncDto> getCards(String key) {
        return route(key).getCards(key);
    }

    @Override
    public List<CardApprovalSyncDto> getCardApprovals(String key, long cardId) {
        return route(key).getCardApprovals(key, cardId);
    }

    @Override
    public List<CardApprovalSyncDto> getCardApprovals(String key, long cardId, String yearMonth) {
        return route(key).getCardApprovals(key, cardId, yearMonth);
    }

    @Override
    public List<CardBillSyncDto> getCardBills(String key, long cardId) {
        return route(key).getCardBills(key, cardId);
    }
}
