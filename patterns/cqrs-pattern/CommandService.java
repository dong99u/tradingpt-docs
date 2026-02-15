package com.example.tradingpt.domain.memo.service.command;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.tradingpt.domain.memo.dto.request.MemoRequestDTO;
import com.example.tradingpt.domain.memo.dto.response.MemoResponseDTO;
import com.example.tradingpt.domain.memo.entity.Memo;
import com.example.tradingpt.domain.memo.exception.MemoErrorStatus;
import com.example.tradingpt.domain.memo.exception.MemoException;
import com.example.tradingpt.domain.memo.repository.MemoRepository;
import com.example.tradingpt.domain.user.entity.Customer;
import com.example.tradingpt.domain.user.exception.UserErrorStatus;
import com.example.tradingpt.domain.user.exception.UserException;
import com.example.tradingpt.domain.user.repository.CustomerRepository;

import lombok.RequiredArgsConstructor;

/**
 * Command Service - Write Operations (Create, Update, Delete)
 *
 * Demonstrates:
 * 1. @Transactional at METHOD level - each write operation has its own transaction
 * 2. Entity business methods for updates (JPA Dirty Checking)
 * 3. Builder pattern only for NEW entity creation (save() required)
 * 4. No save() needed for updates within @Transactional
 */
@Service
@RequiredArgsConstructor
public class MemoCommandServiceImpl implements MemoCommandService {

    private final MemoRepository memoRepository;
    private final CustomerRepository customerRepository;

    /**
     * Create or update memo (Upsert pattern).
     * - New memo: Builder + save() (new entity requires explicit persist)
     * - Existing memo: Entity business method + dirty checking (no save())
     */
    @Override
    @Transactional  // Method-level: only write methods get full transaction
    public MemoResponseDTO createOrUpdateMemo(Long customerId, MemoRequestDTO request) {
        Customer customer = customerRepository.findById(customerId)
            .orElseThrow(() -> new UserException(UserErrorStatus.CUSTOMER_NOT_FOUND));

        Memo memo = memoRepository.findByCustomer_Id(customerId)
            .map(existingMemo -> updateMemo(existingMemo, request))
            .orElseGet(() -> createMemo(customer, request));

        return MemoResponseDTO.from(memo);
    }

    /**
     * Delete memo.
     */
    @Override
    @Transactional
    public void deleteMemo(Long customerId) {
        Memo memo = memoRepository.findByCustomer_Id(customerId)
            .orElseThrow(() -> new MemoException(MemoErrorStatus.MEMO_NOT_FOUND));
        memoRepository.delete(memo);
    }

    // --- Private helpers ---

    private Memo createMemo(Customer customer, MemoRequestDTO request) {
        Memo memo = Memo.builder()
            .customer(customer)
            .title(request.getTitle())
            .content(request.getContent())
            .build();
        return memoRepository.save(memo);  // New entity: save() required
    }

    private Memo updateMemo(Memo memo, MemoRequestDTO request) {
        memo.update(request.getTitle(), request.getContent());  // Dirty checking
        return memo;  // No save() needed
    }
}
