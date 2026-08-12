package com.chj.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.chj.anno.AutoFill;
import com.chj.mapper.ArticleMapper;
import com.chj.pojo.Article;
import com.chj.pojo.CategoryStats;
import com.chj.pojo.PageBean;
import com.chj.service.ArticleService;
import com.chj.utils.ThreadLocalUtil;
import com.chj.vo.ArticleVO;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import jakarta.websocket.OnClose;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ArticleServiceImpl implements ArticleService {
    @Autowired
    private ArticleMapper articleMapper;
    @Autowired
    private EmbeddingModel embeddingModel;
    @Override
    @AutoFill(AutoFill.OperationType.INSERT)
    public void add(Article article) {
        // 生成并设置 embedding
        article.setEmbedding(generateEmbedding(article.getTitle(), article.getContent()));
        articleMapper.add(article);
    }

    @Override
    public PageBean<Article> listByCursor(Integer lastId, Integer pageSize, Integer categoryId, String state,Integer userId) {
        List<Article> articles = articleMapper.listByIdCursor(lastId, pageSize + 1,userId, categoryId, state);
        boolean hasMore = articles.size() > pageSize;
        List<Article> items = hasMore ? articles.subList(0, pageSize) : articles;
        PageBean<Article> pb = new PageBean<>();
        pb.setItems(items);
        pb.setHasMore(hasMore);
        return pb;
    }

    @Override
    @AutoFill(AutoFill.OperationType.UPDATE)
    public void update(Article article) {
        // 更新时重新生成 embedding
        article.setEmbedding(generateEmbedding(article.getTitle(), article.getContent()));
        articleMapper.update(article);
    }

    @Override
    public Article findById(Integer id) {
        return articleMapper.findById(id);
    }

    @Override
    public void deleteById(Integer id) {
        articleMapper.deleteById(id);
    }

    @Override
    public int getTotal() {
        Map<String,Object> map = ThreadLocalUtil.get();
        return articleMapper.getTotal((Integer) map.get("id"));
    }

    @Override
    public List<CategoryStats> getArticleStats() {
        Map<String,Object> map = ThreadLocalUtil.get();
        return articleMapper.getArticleStats((Integer)map.get("id"));
    }

    @Override
    public List<ArticleVO> listArticleByCategoryId(Integer categoryId) {
        Map<String,Object> map = ThreadLocalUtil.get();
        return articleMapper.listArticleByCategoryId(categoryId,(Integer)map.get("id"));
    }

    @Override
    public PageBean<Article> listByCursorByPrivate(Integer lastId, Integer pageSize, Integer categoryId, String state) {
        Map<String,Object> map = ThreadLocalUtil.get();
        return listByCursor(lastId, pageSize, categoryId, state, (Integer) map.get("id"));
    }

    @Override
    public PageBean<Article> list(Integer pageSize, Integer pageNum, Integer categoryId, String status, String data, Integer userId) {
        PageHelper.startPage(pageNum, pageSize);
        float[] embedding = generateEmbedding(data, "");
        Page<Article> page=(Page<Article>) articleMapper.list(userId, categoryId, status,embedding);
        PageBean<Article> pq=new PageBean<>();
        pq.setTotal(page.getTotal());
        pq.setItems(page.getResult());
        return pq;
    }

    @Override
    public PageBean<Article> listByPrivate(Integer pageSize, Integer pageNum, Integer categoryId, String state, String data) {
        Map<String,Object> map = ThreadLocalUtil.get();
        return list(pageSize, pageNum, categoryId, state, data, (Integer) map.get("id"));
    }

    @Override
    public ArticleVO findByTitle(String title) {
        Map<String,Object> map = ThreadLocalUtil.get();
        return articleMapper.findByTitle(title,(Integer)map.get("id"));
    }

    @Override
    public List<ArticleVO> listArticle(List<String> data) {
        Map<String,Object> map = ThreadLocalUtil.get();
        Integer userId = (Integer) map.get("id");

        // 1. BM25 全文搜索（原有逻辑）
        List<ArticleVO> bm25Results = articleMapper.listArticle(data, userId);

        // 2. 向量搜索（补充 BM25 查不到的语义匹配结果，出错则降级为 BM25 结果）
        try {
            Set<Integer> bm25Ids = bm25Results.stream().map(ArticleVO::getId).collect(Collectors.toSet());
            String combinedQuery = String.join(" ", data);
            float[] queryVector = generateEmbedding(combinedQuery, "");
            String vectorLiteral = floatArrayToVectorLiteral(queryVector);
            List<ArticleVO> vectorResults = articleMapper.listArticleByVector(vectorLiteral, userId, 10);

            // 3. 合并结果：先 BM25 结果，再去重补充向量搜索的额外结果
            List<ArticleVO> merged = new ArrayList<>(bm25Results);
            for (ArticleVO a : vectorResults) {
                if (!bm25Ids.contains(a.getId())) {
                    merged.add(a);
                }
            }
            return merged;
        } catch (Exception e) {
            log.warn("向量搜索失败，降级为 BM25 全文搜索: {}", e.getMessage());
            return bm25Results;
        }
    }
    /**
     * 生成文本的 embedding 向量
     * @param title 文章标题
     * @param content 文章内容
     * @return float[] 向量数组
     */
    private float[] generateEmbedding(String title, String content) {
        String text = (title != null ? title : "") + " " + (content != null ? content : "");
        EmbeddingRequest request = new EmbeddingRequest(List.of(text.trim()),null);
        return embeddingModel.call(request)
                .getResult().getOutput();
    }

    private String floatArrayToVectorLiteral(float[] array) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < array.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(array[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
