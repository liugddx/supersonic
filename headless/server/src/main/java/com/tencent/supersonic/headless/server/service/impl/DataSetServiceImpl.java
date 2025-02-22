package com.tencent.supersonic.headless.server.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.tencent.supersonic.auth.api.authentication.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.enums.StatusEnum;
import com.tencent.supersonic.common.pojo.enums.TypeEnums;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.util.BeanMapper;
import com.tencent.supersonic.headless.api.pojo.QueryConfig;
import com.tencent.supersonic.headless.api.pojo.DataSetDetail;
import com.tencent.supersonic.headless.api.pojo.request.QuerySqlReq;
import com.tencent.supersonic.headless.api.pojo.request.QueryStructReq;
import com.tencent.supersonic.headless.api.pojo.request.QueryDataSetReq;
import com.tencent.supersonic.headless.api.pojo.request.SemanticQueryReq;
import com.tencent.supersonic.headless.api.pojo.request.DataSetReq;
import com.tencent.supersonic.headless.api.pojo.response.DimensionResp;
import com.tencent.supersonic.headless.api.pojo.response.DomainResp;
import com.tencent.supersonic.headless.api.pojo.response.MetricResp;
import com.tencent.supersonic.headless.api.pojo.response.DataSetResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.DataSetDO;
import com.tencent.supersonic.headless.server.persistence.mapper.DataSetDOMapper;
import com.tencent.supersonic.headless.server.pojo.MetaFilter;
import com.tencent.supersonic.headless.server.service.DimensionService;
import com.tencent.supersonic.headless.server.service.DomainService;
import com.tencent.supersonic.headless.server.service.MetricService;
import com.tencent.supersonic.headless.server.service.DataSetService;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DataSetServiceImpl
        extends ServiceImpl<DataSetDOMapper, DataSetDO> implements DataSetService {

    protected final Cache<MetaFilter, List<DataSetResp>> dataSetSchemaCache =
            CacheBuilder.newBuilder().expireAfterWrite(30, TimeUnit.SECONDS).build();

    @Autowired
    private DomainService domainService;

    @Lazy
    @Autowired
    private DimensionService dimensionService;

    @Lazy
    @Autowired
    private MetricService metricService;

    @Override
    public DataSetResp save(DataSetReq dataSetReq, User user) {
        dataSetReq.createdBy(user.getName());
        DataSetDO dataSetDO = convert(dataSetReq);
        dataSetDO.setStatus(StatusEnum.ONLINE.getCode());
        DataSetResp dataSetResp = convert(dataSetDO);
        conflictCheck(dataSetResp);
        save(dataSetDO);
        return dataSetResp;
    }

    @Override
    public DataSetResp update(DataSetReq dataSetReq, User user) {
        dataSetReq.updatedBy(user.getName());
        DataSetDO dataSetDO = convert(dataSetReq);
        DataSetResp dataSetResp = convert(dataSetDO);
        conflictCheck(dataSetResp);
        updateById(dataSetDO);
        return dataSetResp;
    }

    @Override
    public DataSetResp getDataSet(Long id) {
        DataSetDO dataSetDO = getById(id);
        return convert(dataSetDO);
    }

    @Override
    public List<DataSetResp> getDataSetList(MetaFilter metaFilter) {
        QueryWrapper<DataSetDO> wrapper = new QueryWrapper<>();
        if (metaFilter.getDomainId() != null) {
            wrapper.lambda().eq(DataSetDO::getDomainId, metaFilter.getDomainId());
        }
        if (!CollectionUtils.isEmpty(metaFilter.getIds())) {
            wrapper.lambda().in(DataSetDO::getId, metaFilter.getIds());
        }
        if (metaFilter.getStatus() != null) {
            wrapper.lambda().eq(DataSetDO::getStatus, metaFilter.getStatus());
        }
        wrapper.lambda().ne(DataSetDO::getStatus, StatusEnum.DELETED.getCode());
        return list(wrapper).stream().map(this::convert).collect(Collectors.toList());
    }

    @Override
    public void delete(Long id, User user) {
        DataSetDO dataSetDO = getById(id);
        dataSetDO.setStatus(StatusEnum.DELETED.getCode());
        dataSetDO.setUpdatedBy(user.getName());
        dataSetDO.setUpdatedAt(new Date());
        updateById(dataSetDO);
    }

    @Override
    public List<DataSetResp> getDataSets(User user) {
        List<DataSetResp> dataSetResps = getDataSetList(new MetaFilter());
        return getDataSetFilterByAuth(dataSetResps, user);
    }

    @Override
    public List<DataSetResp> getDataSetsInheritAuth(User user, Long domainId) {
        List<DataSetResp> dataSetResps = getDataSetList(new MetaFilter());
        List<DataSetResp> inheritAuthFormDomain = getDataSetFilterByDomainAuth(dataSetResps, user);
        Set<DataSetResp> dataSetRespSet = new HashSet<>(inheritAuthFormDomain);
        List<DataSetResp> dataSetFilterByAuth = getDataSetFilterByAuth(dataSetResps, user);
        dataSetRespSet.addAll(dataSetFilterByAuth);
        if (domainId != null && domainId > 0) {
            dataSetRespSet = dataSetRespSet.stream().filter(modelResp ->
                    modelResp.getDomainId().equals(domainId)).collect(Collectors.toSet());
        }
        return dataSetRespSet.stream().sorted(Comparator.comparingLong(DataSetResp::getId))
                .collect(Collectors.toList());
    }

    private List<DataSetResp> getDataSetFilterByAuth(List<DataSetResp> dataSetResps, User user) {
        return dataSetResps.stream()
                .filter(dataSetResp -> checkAdminPermission(user, dataSetResp))
                .collect(Collectors.toList());
    }

    private List<DataSetResp> getDataSetFilterByDomainAuth(List<DataSetResp> dataSetResps, User user) {
        Set<DomainResp> domainResps = domainService.getDomainAuthSet(user, AuthType.ADMIN);
        if (CollectionUtils.isEmpty(domainResps)) {
            return Lists.newArrayList();
        }
        Set<Long> domainIds = domainResps.stream().map(DomainResp::getId).collect(Collectors.toSet());
        return dataSetResps.stream().filter(dataSetResp ->
                domainIds.contains(dataSetResp.getDomainId())).collect(Collectors.toList());
    }

    private DataSetResp convert(DataSetDO dataSetDO) {
        DataSetResp dataSetResp = new DataSetResp();
        BeanMapper.mapper(dataSetDO, dataSetResp);
        dataSetResp.setDataSetDetail(JSONObject.parseObject(dataSetDO.getDataSetDetail(), DataSetDetail.class));
        if (dataSetDO.getQueryConfig() != null) {
            dataSetResp.setQueryConfig(JSONObject.parseObject(dataSetDO.getQueryConfig(), QueryConfig.class));
        }
        dataSetResp.setAdmins(StringUtils.isBlank(dataSetDO.getAdmin())
                ? Lists.newArrayList() : Arrays.asList(dataSetDO.getAdmin().split(",")));
        dataSetResp.setAdminOrgs(StringUtils.isBlank(dataSetDO.getAdminOrg())
                ? Lists.newArrayList() : Arrays.asList(dataSetDO.getAdminOrg().split(",")));
        dataSetResp.setTypeEnum(TypeEnums.DATASET);
        return dataSetResp;
    }

    private DataSetDO convert(DataSetReq dataSetReq) {
        DataSetDO dataSetDO = new DataSetDO();
        BeanMapper.mapper(dataSetReq, dataSetDO);
        dataSetDO.setDataSetDetail(JSONObject.toJSONString(dataSetReq.getDataSetDetail()));
        dataSetDO.setQueryConfig(JSONObject.toJSONString(dataSetReq.getQueryConfig()));
        return dataSetDO;
    }

    public SemanticQueryReq convert(QueryDataSetReq queryDataSetReq) {
        SemanticQueryReq queryReq = new QueryStructReq();
        if (StringUtils.isNotBlank(queryDataSetReq.getSql())) {
            queryReq = new QuerySqlReq();
        }
        BeanUtils.copyProperties(queryDataSetReq, queryReq);
        return queryReq;
    }

    public static boolean checkAdminPermission(User user, DataSetResp dataSetResp) {
        List<String> admins = dataSetResp.getAdmins();
        if (user.isSuperAdmin()) {
            return true;
        }
        String userName = user.getName();
        return admins.contains(userName) || dataSetResp.getCreatedBy().equals(userName);
    }

    @Override
    public Map<Long, List<Long>> getModelIdToDataSetIds(List<Long> dataSetIds) {
        MetaFilter metaFilter = new MetaFilter();
        metaFilter.setStatus(StatusEnum.ONLINE.getCode());
        metaFilter.setIds(dataSetIds);
        List<DataSetResp> dataSetList = dataSetSchemaCache.getIfPresent(metaFilter);
        if (CollectionUtils.isEmpty(dataSetList)) {
            dataSetList = getDataSetList(metaFilter);
            dataSetSchemaCache.put(metaFilter, dataSetList);
        }
        return dataSetList.stream()
                .flatMap(
                        dataSetResp -> dataSetResp.getAllModels().stream().map(modelId ->
                                Pair.of(modelId, dataSetResp.getId())))
                .collect(Collectors.groupingBy(Pair::getLeft,
                        Collectors.mapping(Pair::getRight, Collectors.toList())));
    }

    private void conflictCheck(DataSetResp dataSetResp) {
        List<Long> allDimensionIds = dataSetResp.getAllDimensions();
        List<Long> allMetricIds = dataSetResp.getAllMetrics();
        MetaFilter metaFilter = new MetaFilter();
        if (!CollectionUtils.isEmpty(allDimensionIds)) {
            metaFilter.setIds(allDimensionIds);
            List<DimensionResp> dimensionResps = dimensionService.getDimensions(metaFilter);
            List<String> duplicateDimensionNames = findDuplicates(dimensionResps, DimensionResp::getName);
            List<String> duplicateDimensionBizNames = findDuplicates(dimensionResps, DimensionResp::getBizName);
            if (!duplicateDimensionNames.isEmpty()) {
                throw new InvalidArgumentException("存在相同的维度名: " + duplicateDimensionNames);
            }
            if (!duplicateDimensionBizNames.isEmpty()) {
                throw new InvalidArgumentException("存在相同的维度英文名: " + duplicateDimensionBizNames);
            }
        }
        if (!CollectionUtils.isEmpty(allMetricIds)) {
            metaFilter.setIds(allMetricIds);
            List<MetricResp> metricResps = metricService.getMetrics(metaFilter);
            List<String> duplicateMetricNames = findDuplicates(metricResps, MetricResp::getName);
            List<String> duplicateMetricBizNames = findDuplicates(metricResps, MetricResp::getBizName);

            if (!duplicateMetricNames.isEmpty()) {
                throw new InvalidArgumentException("存在相同的指标名: " + duplicateMetricNames);
            }
            if (!duplicateMetricBizNames.isEmpty()) {
                throw new InvalidArgumentException("存在相同的指标英文名: " + duplicateMetricBizNames);
            }
        }
    }

    private <T, R> List<String> findDuplicates(List<T> list, Function<T, R> keyExtractor) {
        return list.stream()
                .collect(Collectors.groupingBy(keyExtractor, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .map(Object::toString)
                .collect(Collectors.toList());
    }
}
