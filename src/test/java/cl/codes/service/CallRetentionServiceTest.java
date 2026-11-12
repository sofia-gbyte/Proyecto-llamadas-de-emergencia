package cl.codes.service;

import cl.codes.model.Call;
import cl.codes.repository.CallRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CallRetentionServiceTest {
    @Mock
    private CallRepository callRepository;

    @Test
    void deletesOldCasesWithoutExcludingActiveOrArchivedStatuses() {
        LocalDateTime cutoff = LocalDateTime.now().minusMonths(2);
        Call pending = call(1L, cutoff.minusDays(1), false, false);
        Call archived = call(2L, cutoff.minusDays(2), true, false);
        when(callRepository.findTop100ByCreatedAtBeforeOrderByCreatedAtAsc(cutoff))
                .thenReturn(List.of(pending, archived));

        List<CallRetentionService.ExpiredCall> deleted =
                new CallRetentionService(callRepository).deleteExpiredBatch(cutoff);

        assertEquals(List.of(1L, 2L), deleted.stream().map(CallRetentionService.ExpiredCall::id).toList());
        verify(callRepository).deleteAllInBatch(List.of(pending, archived));
    }

    private Call call(Long id, LocalDateTime createdAt, boolean archived, boolean assigned) {
        Call call = new Call();
        call.setId(id);
        call.setCreatedAt(createdAt);
        call.setDeleted(archived);
        call.setAssigned(assigned);
        return call;
    }
}
