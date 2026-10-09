package com.kb.tangtang.transaction.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MerchantDirectoryMapperXmlTest {

    private static final String RESOURCE = "mapper/transaction/MerchantDirectoryMapper.xml";

    @Test
    @DisplayName("findByNames 쿼리가 등록되고 MerchantDirectoryEntry 로 매핑된다")
    void parsesFindByNamesStatement() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream inputStream = Resources.getResourceAsStream(RESOURCE)) {
            new XMLMapperBuilder(inputStream, configuration, RESOURCE,
                    configuration.getSqlFragments()).parse();
        }

        String id = MerchantDirectoryMapper.class.getName() + ".findByNames";
        assertTrue(configuration.hasStatement(id));
        MappedStatement statement = configuration.getMappedStatement(id);
        assertEquals("com.kb.tangtang.transaction.domain.MerchantDirectoryEntry",
                statement.getResultMaps().get(0).getType().getName());
    }
}
