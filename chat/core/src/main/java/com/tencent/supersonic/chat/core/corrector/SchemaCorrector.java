package com.tencent.supersonic.chat.core.corrector;

import com.tencent.supersonic.chat.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.chat.api.pojo.SemanticSchema;
import com.tencent.supersonic.chat.api.pojo.response.SqlInfo;
import com.tencent.supersonic.chat.core.parser.sql.llm.ParseResult;
import com.tencent.supersonic.chat.core.pojo.QueryContext;
import com.tencent.supersonic.chat.core.query.llm.s2sql.LLMReq.ElementValue;
import com.tencent.supersonic.common.pojo.Constants;
import com.tencent.supersonic.common.pojo.enums.FilterOperatorEnum;
import com.tencent.supersonic.common.pojo.enums.TimeDimensionEnum;
import com.tencent.supersonic.common.util.DateUtils;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.common.util.jsqlparser.AggregateEnum;
import com.tencent.supersonic.common.util.jsqlparser.FieldExpression;
import com.tencent.supersonic.common.util.jsqlparser.SqlRemoveHelper;
import com.tencent.supersonic.common.util.jsqlparser.SqlReplaceHelper;
import com.tencent.supersonic.common.util.jsqlparser.SqlSelectHelper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Perform schema corrections on the Schema information in S2SQL.
 */
@Slf4j
public class SchemaCorrector extends BaseSemanticCorrector {

    @Override
    public void doCorrect(QueryContext queryContext, SemanticParseInfo semanticParseInfo) {

        removeFilterIfNotInLinkingValue(queryContext, semanticParseInfo);

        correctAggFunction(semanticParseInfo);

        replaceAlias(semanticParseInfo);

        updateFieldNameByLinkingValue(semanticParseInfo);

        updateFieldValueByLinkingValue(semanticParseInfo);

        correctFieldName(queryContext, semanticParseInfo);
    }

    private void correctAggFunction(SemanticParseInfo semanticParseInfo) {
        Map<String, String> aggregateEnum = AggregateEnum.getAggregateEnum();
        SqlInfo sqlInfo = semanticParseInfo.getSqlInfo();
        String sql = SqlReplaceHelper.replaceFunction(sqlInfo.getCorrectS2SQL(), aggregateEnum);
        sqlInfo.setCorrectS2SQL(sql);
    }

    private void replaceAlias(SemanticParseInfo semanticParseInfo) {
        SqlInfo sqlInfo = semanticParseInfo.getSqlInfo();
        String replaceAlias = SqlReplaceHelper.replaceAlias(sqlInfo.getCorrectS2SQL());
        sqlInfo.setCorrectS2SQL(replaceAlias);
    }

    private void correctFieldName(QueryContext queryContext, SemanticParseInfo semanticParseInfo) {
        Map<String, String> fieldNameMap = getFieldNameMap(queryContext, semanticParseInfo.getDataSetId());
        SqlInfo sqlInfo = semanticParseInfo.getSqlInfo();
        String sql = SqlReplaceHelper.replaceFields(sqlInfo.getCorrectS2SQL(), fieldNameMap);
        sqlInfo.setCorrectS2SQL(sql);
    }

    private void updateFieldNameByLinkingValue(SemanticParseInfo semanticParseInfo) {
        List<ElementValue> linking = getLinkingValues(semanticParseInfo);
        if (CollectionUtils.isEmpty(linking)) {
            return;
        }

        Map<String, Set<String>> fieldValueToFieldNames = linking.stream().collect(
                Collectors.groupingBy(ElementValue::getFieldValue,
                        Collectors.mapping(ElementValue::getFieldName, Collectors.toSet())));

        SqlInfo sqlInfo = semanticParseInfo.getSqlInfo();

        String sql = SqlReplaceHelper.replaceFieldNameByValue(sqlInfo.getCorrectS2SQL(), fieldValueToFieldNames);
        sqlInfo.setCorrectS2SQL(sql);
    }

    private List<ElementValue> getLinkingValues(SemanticParseInfo semanticParseInfo) {
        Object context = semanticParseInfo.getProperties().get(Constants.CONTEXT);
        if (Objects.isNull(context)) {
            return null;
        }

        ParseResult parseResult = JsonUtil.toObject(JsonUtil.toString(context), ParseResult.class);
        if (Objects.isNull(parseResult) || Objects.isNull(parseResult.getLlmReq())) {
            return null;
        }
        return parseResult.getLinkingValues();
    }

    private void updateFieldValueByLinkingValue(SemanticParseInfo semanticParseInfo) {
        List<ElementValue> linking = getLinkingValues(semanticParseInfo);
        if (CollectionUtils.isEmpty(linking)) {
            return;
        }

        Map<String, Map<String, String>> filedNameToValueMap = linking.stream().collect(
                Collectors.groupingBy(ElementValue::getFieldName,
                        Collectors.mapping(ElementValue::getFieldValue, Collectors.toMap(
                                oldValue -> oldValue,
                                newValue -> newValue,
                                (existingValue, newValue) -> newValue)
                        )));

        SqlInfo sqlInfo = semanticParseInfo.getSqlInfo();
        String sql = SqlReplaceHelper.replaceValue(sqlInfo.getCorrectS2SQL(), filedNameToValueMap, false);
        sqlInfo.setCorrectS2SQL(sql);
    }

    public void removeFilterIfNotInLinkingValue(QueryContext queryContext, SemanticParseInfo semanticParseInfo) {
        SqlInfo sqlInfo = semanticParseInfo.getSqlInfo();
        String correctS2SQL = sqlInfo.getCorrectS2SQL();
        List<FieldExpression> whereExpressionList = SqlSelectHelper.getWhereExpressions(correctS2SQL);
        if (CollectionUtils.isEmpty(whereExpressionList)) {
            return;
        }
        List<ElementValue> linkingValues = getLinkingValues(semanticParseInfo);
        SemanticSchema semanticSchema = queryContext.getSemanticSchema();
        Set<String> dimensions = getDimensions(semanticParseInfo.getDataSetId(), semanticSchema);

        if (CollectionUtils.isEmpty(linkingValues)) {
            linkingValues = new ArrayList<>();
        }
        Set<String> linkingFieldNames = linkingValues.stream().map(linking -> linking.getFieldName())
                .collect(Collectors.toSet());

        Set<String> removeFieldNames = whereExpressionList.stream()
                .filter(fieldExpression -> StringUtils.isBlank(fieldExpression.getFunction()))
                .filter(fieldExpression -> !TimeDimensionEnum.containsTimeDimension(fieldExpression.getFieldName()))
                .filter(fieldExpression -> FilterOperatorEnum.EQUALS.getValue().equals(fieldExpression.getOperator()))
                .filter(fieldExpression -> dimensions.contains(fieldExpression.getFieldName()))
                .filter(fieldExpression -> !DateUtils.isAnyDateString(fieldExpression.getFieldValue().toString()))
                .filter(fieldExpression -> !linkingFieldNames.contains(fieldExpression.getFieldName()))
                .map(fieldExpression -> fieldExpression.getFieldName()).collect(Collectors.toSet());

        String sql = SqlRemoveHelper.removeWhereCondition(correctS2SQL, removeFieldNames);
        sqlInfo.setCorrectS2SQL(sql);
    }

}
