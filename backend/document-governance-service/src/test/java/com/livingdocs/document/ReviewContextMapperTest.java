package com.livingdocs.document;

import com.livingdocs.document.entity.DocumentVersionEntity;
import com.livingdocs.document.mapper.ReviewContextMapper;
import com.livingdocs.document.model.DocumentState;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewContextMapperTest {
    @Test void baselineWithoutContentKeepsUnknownRevisionNullInsteadOfInventingZero() {
        var row = mock(DocumentVersionEntity.class);
        UUID id = UUID.randomUUID();
        when(row.getId()).thenReturn(id); when(row.getVersionNo()).thenReturn(1); when(row.getState()).thenReturn(DocumentState.PUBLISHED);
        var baseline = Mappers.getMapper(ReviewContextMapper.class).baseline(row, null);
        assertThat(baseline.getVersionId()).isEqualTo(id);
        assertThat(baseline.getContent()).isNull();
        assertThat(baseline.getContentRevisionNo()).isNull();
        assertThat(baseline.getContentRef()).isNull();
    }
}
