package com.example.tradingpt.domain.memo.service.query;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.tradingpt.domain.memo.dto.response.MemoResponseDTO;
import com.example.tradingpt.domain.memo.entity.Memo;
import com.example.tradingpt.domain.memo.exception.MemoErrorStatus;
import com.example.tradingpt.domain.memo.exception.MemoException;
import com.example.tradingpt.domain.memo.repository.MemoRepository;

import lombok.RequiredArgsConstructor;

/**
 * Query Service - Read-Only Operations
 *
 * Demonstrates:
 * 1. @Transactional(readOnly = true) at CLASS level - all methods inherit read-only
 * 2. No dirty checking overhead - Hibernate skips snapshot comparison
 * 3. Flush skipped - no need to synchronize persistence context
 * 4. Can route to read replica in DataSource routing setups
 * 5. Uses DTO static factory method (from) for entity-to-DTO conversion
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)  // Class-level: all methods are read-only
public class MemoQueryServiceImpl implements MemoQueryService {

    private final MemoRepository memoRepository;

    /**
     * Retrieve memo by customer ID.
     * Inherits class-level @Transactional(readOnly = true).
     */
    @Override
    public MemoResponseDTO getMemo(Long customerId) {
        Memo memo = memoRepository.findByCustomer_Id(customerId)
            .orElseThrow(() -> new MemoException(MemoErrorStatus.MEMO_NOT_FOUND));
        return MemoResponseDTO.from(memo);  // Static factory method pattern
    }
}
