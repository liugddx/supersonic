package com.tencent.supersonic.chat.core.parser.sql.llm;

import com.tencent.supersonic.chat.api.pojo.SemanticSchema;
import com.tencent.supersonic.chat.core.agent.NL2SQLTool;
import com.tencent.supersonic.chat.core.parser.SemanticParser;
import com.tencent.supersonic.chat.core.pojo.ChatContext;
import com.tencent.supersonic.chat.core.pojo.QueryContext;
import com.tencent.supersonic.chat.core.query.llm.s2sql.LLMReq;
import com.tencent.supersonic.chat.core.query.llm.s2sql.LLMReq.ElementValue;
import com.tencent.supersonic.chat.core.query.llm.s2sql.LLMResp;
import com.tencent.supersonic.chat.core.query.llm.s2sql.LLMSqlResp;
import com.tencent.supersonic.common.util.ContextUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
public class LLMSqlParser implements SemanticParser {

    @Override
    public void parse(QueryContext queryCtx, ChatContext chatCtx) {
        LLMRequestService requestService = ContextUtils.getBean(LLMRequestService.class);
        //1.determine whether to skip this parser.
        if (requestService.isSkip(queryCtx)) {
            return;
        }
        try {
            //2.get dataSetId from queryCtx and chatCtx.
            Long dataSetId = requestService.getDataSetId(queryCtx);
            if (dataSetId == null) {
                return;
            }
            //3.get agent tool and determine whether to skip this parser.
            NL2SQLTool commonAgentTool = requestService.getParserTool(queryCtx, dataSetId);
            if (Objects.isNull(commonAgentTool)) {
                log.info("no tool in this agent, skip {}", LLMSqlParser.class);
                return;
            }
            //4.construct a request, call the API for the large model, and retrieve the results.
            List<ElementValue> linkingValues = requestService.getValueList(queryCtx, dataSetId);
            SemanticSchema semanticSchema = queryCtx.getSemanticSchema();
            LLMReq llmReq = requestService.getLlmReq(queryCtx, dataSetId, semanticSchema, linkingValues);
            LLMResp llmResp = requestService.requestLLM(llmReq, dataSetId);

            if (Objects.isNull(llmResp)) {
                return;
            }
            //5. deduplicate the SQL result list and build parserInfo
            LLMResponseService responseService = ContextUtils.getBean(LLMResponseService.class);
            Map<String, LLMSqlResp> deduplicationSqlResp = responseService.getDeduplicationSqlResp(llmResp);
            ParseResult parseResult = ParseResult.builder()
                    .dataSetId(dataSetId)
                    .commonAgentTool(commonAgentTool)
                    .llmReq(llmReq)
                    .llmResp(llmResp)
                    .linkingValues(linkingValues)
                    .build();

            if (MapUtils.isEmpty(deduplicationSqlResp)) {
                responseService.addParseInfo(queryCtx, parseResult, llmResp.getSqlOutput(), 1D);
            } else {
                deduplicationSqlResp.forEach((sql, sqlResp) -> {
                    responseService.addParseInfo(queryCtx, parseResult, sql, sqlResp.getSqlWeight());
                });
            }

        } catch (Exception e) {
            log.error("parse", e);
        }
    }

}
