package com.tencent.supersonic.headless.server.service;

import com.tencent.supersonic.auth.api.authentication.pojo.User;
import com.tencent.supersonic.headless.api.pojo.request.QueryDataSetReq;
import com.tencent.supersonic.headless.api.pojo.request.SemanticQueryReq;
import com.tencent.supersonic.headless.api.pojo.request.DataSetReq;
import com.tencent.supersonic.headless.api.pojo.response.DataSetResp;
import com.tencent.supersonic.headless.server.pojo.MetaFilter;

import java.util.List;
import java.util.Map;

public interface DataSetService {

    DataSetResp save(DataSetReq dataSetReq, User user);

    DataSetResp update(DataSetReq dataSetReq, User user);

    DataSetResp getDataSet(Long id);

    List<DataSetResp> getDataSetList(MetaFilter metaFilter);

    void delete(Long id, User user);

    Map<Long, List<Long>> getModelIdToDataSetIds(List<Long> dataSetIds);

    List<DataSetResp> getDataSets(User user);

    List<DataSetResp> getDataSetsInheritAuth(User user, Long domainId);

    SemanticQueryReq convert(QueryDataSetReq queryDataSetReq);
}
