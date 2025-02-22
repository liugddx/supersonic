package com.tencent.supersonic.headless.core.parser;

import com.google.common.base.Strings;
import com.tencent.supersonic.common.util.StringUtil;
import com.tencent.supersonic.headless.api.pojo.MetricTable;
import com.tencent.supersonic.headless.api.pojo.QueryParam;
import com.tencent.supersonic.headless.api.pojo.enums.AggOption;
import com.tencent.supersonic.headless.api.pojo.request.SqlExecuteReq;
import com.tencent.supersonic.headless.core.parser.converter.HeadlessConverter;
import com.tencent.supersonic.headless.core.pojo.MetricQueryParam;
import com.tencent.supersonic.headless.core.pojo.QueryStatement;
import com.tencent.supersonic.headless.core.pojo.DataSetQueryParam;
import com.tencent.supersonic.headless.core.utils.ComponentFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
@Slf4j
@Primary
public class DefaultQueryParser implements QueryParser {

    public void parse(QueryStatement queryStatement) throws Exception {
        QueryParam queryParam = queryStatement.getQueryParam();
        if (Objects.isNull(queryStatement.getDataSetQueryParam())) {
            queryStatement.setDataSetQueryParam(new DataSetQueryParam());
        }
        if (Objects.isNull(queryStatement.getMetricQueryParam())) {
            queryStatement.setMetricQueryParam(new MetricQueryParam());
        }
        log.info("SemanticConverter before [{}]", queryParam);
        for (HeadlessConverter headlessConverter : ComponentFactory.getSemanticConverters()) {
            if (headlessConverter.accept(queryStatement)) {
                log.info("SemanticConverter accept [{}]", headlessConverter.getClass().getName());
                headlessConverter.convert(queryStatement);
            }
        }
        log.info("SemanticConverter after {} {} {}", queryParam, queryStatement.getDataSetQueryParam(),
                queryStatement.getMetricQueryParam());
        if (!queryStatement.getDataSetQueryParam().getSql().isEmpty()) {
            queryStatement = parser(queryStatement.getDataSetQueryParam(), queryStatement);
        } else {
            queryStatement.getMetricQueryParam().setNativeQuery(queryParam.getQueryType().isNativeAggQuery());
            queryStatement = parser(queryStatement);
        }
        if (Strings.isNullOrEmpty(queryStatement.getSql())
                || Strings.isNullOrEmpty(queryStatement.getSourceId())) {
            throw new RuntimeException("parse Exception: " + queryStatement.getErrMsg());
        }
        String querySql =
                Objects.nonNull(queryStatement.getEnableLimitWrapper()) && queryStatement.getEnableLimitWrapper()
                        ? String.format(SqlExecuteReq.LIMIT_WRAPPER,
                        queryStatement.getSql())
                        : queryStatement.getSql();
        queryStatement.setSql(querySql);
    }

    public QueryStatement parser(DataSetQueryParam dataSetQueryParam, QueryStatement queryStatement) {
        log.info("parser MetricReq [{}] ", dataSetQueryParam);
        try {
            if (!CollectionUtils.isEmpty(dataSetQueryParam.getTables())) {
                List<String[]> tables = new ArrayList<>();
                Boolean isSingleTable = dataSetQueryParam.getTables().size() == 1;
                for (MetricTable metricTable : dataSetQueryParam.getTables()) {
                    QueryStatement tableSql = parserSql(metricTable, isSingleTable, dataSetQueryParam, queryStatement);
                    if (isSingleTable && Objects.nonNull(tableSql.getDataSetQueryParam())
                            && !tableSql.getDataSetSimplifySql().isEmpty()) {
                        queryStatement.setSql(tableSql.getDataSetSimplifySql());
                        queryStatement.setDataSetQueryParam(dataSetQueryParam);
                        return queryStatement;
                    }
                    tables.add(new String[]{metricTable.getAlias(), tableSql.getSql()});
                }
                if (!tables.isEmpty()) {
                    String sql = "";
                    if (dataSetQueryParam.isSupportWith()) {
                        sql = "with " + String.join(",",
                                tables.stream().map(t -> String.format("%s as (%s)", t[0], t[1])).collect(
                                        Collectors.toList())) + "\n" + dataSetQueryParam.getSql();
                    } else {
                        sql = dataSetQueryParam.getSql();
                        for (String[] tb : tables) {
                            sql = StringUtils.replace(sql, tb[0],
                                    "(" + tb[1] + ") " + (dataSetQueryParam.isWithAlias() ? "" : tb[0]), -1);
                        }
                    }
                    queryStatement.setSql(sql);
                    queryStatement.setDataSetQueryParam(dataSetQueryParam);
                    return queryStatement;
                }
            }
        } catch (Exception e) {
            log.error("physicalSql error {}", e);
            queryStatement.setErrMsg(e.getMessage());
        }
        return queryStatement;
    }

    public QueryStatement parser(QueryStatement queryStatement) {
        return parser(queryStatement, AggOption.getAggregation(queryStatement.getMetricQueryParam().isNativeQuery()));
    }

    public QueryStatement parser(QueryStatement queryStatement, AggOption isAgg) {
        MetricQueryParam metricQueryParam = queryStatement.getMetricQueryParam();
        log.info("parser metricQueryReq [{}] isAgg [{}]", metricQueryParam, isAgg);
        try {
            return ComponentFactory.getSqlParser().explain(queryStatement, isAgg);
        } catch (Exception e) {
            queryStatement.setErrMsg(e.getMessage());
            log.error("parser error metricQueryReq[{}] error [{}]", metricQueryParam, e);
        }
        return queryStatement;
    }

    private QueryStatement parserSql(MetricTable metricTable, Boolean isSingleMetricTable,
            DataSetQueryParam dataSetQueryParam,
            QueryStatement queryStatement) throws Exception {
        MetricQueryParam metricReq = new MetricQueryParam();
        metricReq.setMetrics(metricTable.getMetrics());
        metricReq.setDimensions(metricTable.getDimensions());
        metricReq.setWhere(StringUtil.formatSqlQuota(metricTable.getWhere()));
        metricReq.setNativeQuery(!AggOption.isAgg(metricTable.getAggOption()));
        QueryStatement tableSql = new QueryStatement();
        tableSql.setIsS2SQL(false);
        tableSql.setMetricQueryParam(metricReq);
        tableSql.setMinMaxTime(queryStatement.getMinMaxTime());
        tableSql.setEnableOptimize(queryStatement.getEnableOptimize());
        tableSql.setDataSetId(queryStatement.getDataSetId());
        tableSql.setSemanticModel(queryStatement.getSemanticModel());
        if (isSingleMetricTable) {
            tableSql.setDataSetSql(dataSetQueryParam.getSql());
            tableSql.setDataSetAlias(metricTable.getAlias());
        }
        tableSql = parser(tableSql, metricTable.getAggOption());
        if (!tableSql.isOk()) {
            throw new Exception(String.format("parser table [%s] error [%s]", metricTable.getAlias(),
                    tableSql.getErrMsg()));
        }
        queryStatement.setSourceId(tableSql.getSourceId());
        return tableSql;
    }

}
