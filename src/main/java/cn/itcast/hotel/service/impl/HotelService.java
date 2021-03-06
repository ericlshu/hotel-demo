package cn.itcast.hotel.service.impl;

import cn.itcast.hotel.mapper.HotelMapper;
import cn.itcast.hotel.pojo.Hotel;
import cn.itcast.hotel.pojo.HotelDoc;
import cn.itcast.hotel.pojo.PageResult;
import cn.itcast.hotel.pojo.ReqParam;
import cn.itcast.hotel.service.IHotelService;
import cn.itcast.hotel.util.AppConstants;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.common.unit.DistanceUnit;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.search.suggest.SuggestBuilder;
import org.elasticsearch.search.suggest.SuggestBuilders;
import org.elasticsearch.search.suggest.completion.CompletionSuggestion;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class HotelService extends ServiceImpl<HotelMapper, Hotel> implements IHotelService
{
    @Resource
    private RestHighLevelClient restHighLevelClient;

    @Override
    public PageResult search(ReqParam param)
    {
        log.warn("param = {}", param);
        // 1 ??????Request
        SearchRequest searchRequest = new SearchRequest(AppConstants.INDEX_NAME);
        SearchSourceBuilder sourceBuilder = searchRequest.source();
        try
        {
            // 2.1 ??????DSL,??????BoolQuery
            sourceBuilder.query(buildBasicQuery(param));

            // 2.2 ??????
            Integer size = param.getSize();
            Integer page = param.getPage();
            // log.warn("current page : [{}]", page);
            // log.warn("size of page : [{}]", size);
            sourceBuilder.from((page - 1) * size).size(size);

            // 2.3 ???????????????
            String location = param.getLocation();
            if (StringUtils.hasText(location))
            {
                // log.warn("location     : [{}]", location);
                sourceBuilder.sort(SortBuilders
                                           .geoDistanceSort("location", new GeoPoint(location))
                                           .order(SortOrder.ASC)
                                           .unit(DistanceUnit.KILOMETERS));
            }

            // 2.4 ????????????
            sourceBuilder.highlighter(new HighlightBuilder().field("name").requireFieldMatch(false));

            // 3 ??????????????????
            SearchResponse searchResponse = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);

            // 4 ??????????????????
            return handleResponse(searchResponse);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Map<String, List<String>> filters(ReqParam param)
    {
        Map<String, List<String>> result = new HashMap<>();
        try
        {
            // 1 ??????Request
            SearchRequest searchRequest = new SearchRequest(AppConstants.INDEX_NAME);
            SearchSourceBuilder builder = searchRequest.source();
            // 2 ??????DSL????????????
            // 2.1 ??????BoolQuery
            builder.query(buildBasicQuery(param));
            // 2.2 ??????????????????
            builder.size(0);
            // 2.3 ??????????????????
            buildAggregations(builder);
            // 3.????????????
            SearchResponse searchResponse = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);
            Aggregations aggregations = searchResponse.getAggregations();
            if (!ObjectUtils.isEmpty(aggregations))
            {
                result.put("brand", getAggResultByName(aggregations, "brandAgg"));
                result.put("city", getAggResultByName(aggregations, "cityAgg"));
                result.put("starName", getAggResultByName(aggregations, "starAgg"));
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }

        return result;
    }

    @Override
    public List<String> getSuggestion(String prefix)
    {
        try
        {
            // 1.??????request
            SearchRequest searchRequest = new SearchRequest(AppConstants.INDEX_NAME);
            // 2.??????DSL
            SearchSourceBuilder builder = searchRequest.source();
            builder.suggest(new SuggestBuilder().addSuggestion("suggestions",
                                                               SuggestBuilders
                                                                       .completionSuggestion("suggestion")
                                                                       .prefix(prefix)
                                                                       .skipDuplicates(true)
                                                                       .size(10)));
            // 3.????????????
            SearchResponse searchResponse = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);
            // 4.????????????
            CompletionSuggestion suggestion = searchResponse.getSuggest().getSuggestion("suggestions");
            List<CompletionSuggestion.Entry.Option> options = suggestion.getOptions();
            List<String> result = new ArrayList<>(options.size());
            for (CompletionSuggestion.Entry.Option option : options)
                result.add(option.getText().toString());
            log.warn("prefix  : {}", prefix);
            log.info("options : {}", result);
            return result;
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void insertOrUpdateById(Long id)
    {
        try
        {
            // 0.??????id??????????????????
            HotelDoc hotelDoc = new HotelDoc(getById(id));
            // 1.??????Request??????
            IndexRequest request = new IndexRequest(AppConstants.INDEX_NAME).id(hotelDoc.getId().toString());
            // 2.??????Json??????
            request.source(JSON.toJSONString(hotelDoc), XContentType.JSON);
            // 3.????????????
            restHighLevelClient.index(request, RequestOptions.DEFAULT);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void deleteById(Long id)
    {
        try
        {
            // 1.??????Request
            DeleteRequest deleteResult = new DeleteRequest(AppConstants.INDEX_NAME, id.toString());
            // 2.????????????
            restHighLevelClient.delete(deleteResult, RequestOptions.DEFAULT);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private List<String> getAggResultByName(Aggregations aggregations, String aggName)
    {
        List<String> keys = new ArrayList<>();
        // 4.1.???????????????????????????????????????
        Terms termsAgg = aggregations.get(aggName);
        // 4.2.??????buckets
        List<? extends Terms.Bucket> buckets = termsAgg.getBuckets();
        // 4.3.??????
        for (Terms.Bucket bucket : buckets)
        {
            keys.add(bucket.getKeyAsString());
        }
        return keys;
    }

    private void buildAggregations(SearchSourceBuilder builder)
    {
        builder.aggregation(AggregationBuilders
                                    .terms("brandAgg")
                                    .field("brand")
                                    .size(100));
        builder.aggregation(AggregationBuilders
                                    .terms("cityAgg")
                                    .field("city")
                                    .size(100));
        builder.aggregation(AggregationBuilders
                                    .terms("starAgg")
                                    .field("starName")
                                    .size(100));
    }

    /**
     * ??????BoolQuery
     *
     * @param param ????????????
     * @return BoolQuery
     */
    private QueryBuilder buildBasicQuery(ReqParam param)
    {
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();

        String key = param.getKey();
        String city = param.getCity();
        String brand = param.getBrand();
        String starName = param.getStarName();
        Integer minPrice = param.getMinPrice();
        Integer maxPrice = param.getMaxPrice();

        // log.warn("query key    : [{}]", key);
        // log.warn("city         : [{}]", city);
        // log.warn("brand        : [{}]", brand);
        // log.warn("star name    : [{}]", starName);
        // log.warn("min price    : [{}]", minPrice);
        // log.warn("max price    : [{}]", maxPrice);

        // ???????????????
        if (key == null || "".equals(key))
            boolQuery.must(QueryBuilders.matchAllQuery());
        else
            boolQuery.must(QueryBuilders.matchQuery("all", key));

        // ????????????
        if (StringUtils.hasText(city))
            boolQuery.filter(QueryBuilders.termQuery("city", city));
        if (StringUtils.hasText(brand))
            boolQuery.filter(QueryBuilders.termQuery("brand", brand));
        if (StringUtils.hasText(starName))
            boolQuery.filter(QueryBuilders.termQuery("starName", starName));
        if (minPrice != null && maxPrice != null)
            boolQuery.filter(QueryBuilders.rangeQuery("price")
                                     .gte(minPrice)
                                     .lte(maxPrice));

        // ??????????????????
        return QueryBuilders
                .functionScoreQuery(
                        boolQuery,// ???????????????boolQuery
                        new FunctionScoreQueryBuilder.FilterFunctionBuilder[]{// function??????
                                new FunctionScoreQueryBuilder.FilterFunctionBuilder(
                                        QueryBuilders.termQuery("isAD", true),// ????????????
                                        ScoreFunctionBuilders.weightFactorFunction(10)   // ????????????
                                )
                        });
    }

    private PageResult handleResponse(SearchResponse searchResponse) throws IOException
    {
        // 4 ????????????
        SearchHits searchHits = searchResponse.getHits();
        // 4.1 ??????????????????
        long total = searchHits.getTotalHits().value;
        log.warn("total = [{}]", total);
        // 4.2 ?????????????????????
        SearchHit[] hits = searchHits.getHits();
        List<HotelDoc> hotels = new ArrayList<>();
        for (SearchHit hit : hits)
        {
            // 4.3 ??????source
            String json = hit.getSourceAsString();
            // 4.4 ??????json?????????
            HotelDoc hotelDoc = JSON.parseObject(json, HotelDoc.class);
            // 4.5 ???????????????
            Object[] sortValues = hit.getSortValues();
            if (sortValues.length > 0)
                hotelDoc.setDistance(sortValues[0]);
            // 4.6 ??????????????????
            Map<String, HighlightField> highlightFields = hit.getHighlightFields();
            if (!CollectionUtils.isEmpty(highlightFields))
            {
                HighlightField highlightField = highlightFields.get("name");
                if (highlightField != null)
                    hotelDoc.setName(highlightField.getFragments()[0].string());
            }
            log.info("hotelDoc = [{}]", hotelDoc);
            hotels.add(hotelDoc);
        }
        return new PageResult(total, hotels);
    }
}
