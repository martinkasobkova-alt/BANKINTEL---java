package cz.bankintel.service.content;

import cz.bankintel.domain.dto.ContentDtos;
import cz.bankintel.domain.dto.ContentDtos.ArticleCategoryCreateRequest;
import cz.bankintel.domain.dto.ContentDtos.ArticleCategoryResponse;
import cz.bankintel.domain.dto.ContentDtos.ArticleCategoryUpdateRequest;
import cz.bankintel.domain.dto.ContentDtos.ArticleCategoriesReorderBody;
import cz.bankintel.domain.dto.ContentDtos.ArticleCreateRequest;
import cz.bankintel.domain.dto.ContentDtos.ArticleDetailResponse;
import cz.bankintel.domain.dto.ContentDtos.ArticleListResponse;
import cz.bankintel.domain.dto.ContentDtos.ArticleUpdateRequest;
import cz.bankintel.domain.entity.ArticleCategoryEntity;
import cz.bankintel.domain.entity.ArticleEntity;
import cz.bankintel.domain.entity.UserEntity;
import cz.bankintel.repository.ArticleCategoryRepository;
import cz.bankintel.repository.ArticleRepository;
import cz.bankintel.util.IdGenerator;
import cz.bankintel.util.RoleUtils;
import cz.bankintel.util.SlugUtils;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ArticleService {

    private final ArticleRepository articleRepository;
    private final ArticleCategoryRepository categoryRepository;

    public List<ArticleCategoryResponse> listCategories() {
        return categoryRepository.findAllOrdered().stream().map(this::toCategoryResponse).toList();
    }

    @Transactional
    public ArticleCategoryResponse createCategory(ArticleCategoryCreateRequest body) {
        Instant now = Instant.now();
        ArticleCategoryEntity entity = new ArticleCategoryEntity();
        entity.setId(IdGenerator.newId());
        entity.setName(body.name().trim());
        entity.setSlug(uniqueCategorySlug(entity.getName(), null));
        entity.setSortOrder(categoryRepository.maxSortOrder() + 10);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toCategoryResponse(categoryRepository.save(entity));
    }

    @Transactional
    public ArticleCategoryResponse updateCategory(String categoryId, ArticleCategoryUpdateRequest body) {
        ArticleCategoryEntity entity = findCategory(categoryId);
        if (body.name() != null) {
            entity.setName(body.name().trim());
            entity.setSlug(uniqueCategorySlug(entity.getName(), entity.getId()));
        }
        return toCategoryResponse(categoryRepository.save(entity));
    }

    @Transactional
    public void deleteCategory(String categoryId) {
        ArticleCategoryEntity entity = findCategory(categoryId);
        categoryRepository.delete(entity);
        articleRepository.findAll().stream()
                .filter(a -> categoryId.equals(a.getCategoryId()))
                .forEach(a -> {
                    a.setCategoryId(null);
                    articleRepository.save(a);
                });
    }

    @Transactional
    public void reorderCategories(ArticleCategoriesReorderBody body) {
        List<ArticleCategoryEntity> rows = categoryRepository.findAllOrdered();
        var existing = rows.stream().map(ArticleCategoryEntity::getId).collect(Collectors.toSet());
        List<String> ordered =
                body.categoryIds().stream().filter(existing::contains).toList();
        if (ordered.size() != existing.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neúplný seznam podsekcí.");
        }
        Instant now = Instant.now();
        for (int idx = 0; idx < ordered.size(); idx++) {
            String cid = ordered.get(idx);
            ArticleCategoryEntity cat = categoryRepository
                    .findById(cid)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Neúplný seznam podsekcí."));
            cat.setSortOrder((idx + 1) * 10);
            cat.setUpdatedAt(now);
            categoryRepository.save(cat);
        }
    }

    public List<ArticleListResponse> listArticles(UserEntity user, int limit, int skip, String q, String categoryId) {
        boolean publishedOnly = !RoleUtils.isContentManager(user == null ? null : user.getRole());
        String catFilter = blankToNull(categoryId);
        String search = blankToNull(q);
        int cappedLimit = Math.min(Math.max(limit, 1), 100);
        int cappedSkip = Math.min(Math.max(skip, 0), 500);
        var pageable = PageRequest.of(cappedSkip / cappedLimit, cappedLimit);
        Map<String, ArticleCategoryEntity> categories = categoriesMap();
        return articleRepository.findFiltered(publishedOnly, catFilter, search, pageable).stream()
                .skip(cappedSkip % cappedLimit)
                .limit(cappedLimit)
                .map(a -> toListResponse(a, categories))
                .toList();
    }

    public ArticleDetailResponse getArticle(String articleId, UserEntity user) {
        ArticleEntity entity = findArticle(articleId);
        if (!entity.isPublished() && !RoleUtils.isContentManager(user == null ? null : user.getRole())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Zpráva nenalezena.");
        }
        return toDetailResponse(entity, categoriesMap());
    }

    @Transactional
    public ArticleDetailResponse createArticle(ArticleCreateRequest body, UserEntity author) {
        Instant now = Instant.now();
        ArticleEntity entity = new ArticleEntity();
        entity.setId(IdGenerator.newId());
        entity.setSlug(uniqueArticleSlug(body.slug() != null ? body.slug() : body.title(), null));
        entity.setTitle(body.title().trim());
        entity.setSummary(body.summary() == null ? "" : body.summary().trim());
        entity.setBody(body.body().trim());
        entity.setCoverImageUrl(blankToNull(body.coverImageUrl()));
        entity.setCategoryId(validateCategoryId(body.categoryId()));
        entity.setPublished(body.published());
        entity.setPublishedAt(body.published() ? now : null);
        entity.setAuthorId(author.getId());
        entity.setAuthorName(firstNonBlank(author.getName(), author.getEmail(), "Redakce"));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toDetailResponse(articleRepository.save(entity), categoriesMap());
    }

    @Transactional
    public ArticleDetailResponse updateArticle(String articleId, ArticleUpdateRequest body) {
        ArticleEntity entity = findArticle(articleId);
        if (body.title() != null) {
            entity.setTitle(body.title().trim());
        }
        if (body.summary() != null) {
            entity.setSummary(body.summary().trim());
        }
        if (body.body() != null) {
            entity.setBody(body.body().trim());
        }
        if (body.coverImageUrl() != null) {
            entity.setCoverImageUrl(blankToNull(body.coverImageUrl()));
        }
        if (body.categoryId() != null) {
            entity.setCategoryId(validateCategoryId(body.categoryId()));
        }
        if (body.slug() != null) {
            String base = body.slug().isBlank() ? entity.getTitle() : body.slug();
            entity.setSlug(uniqueArticleSlug(base, entity.getId()));
        }
        if (body.published() != null) {
            entity.setPublished(body.published());
            if (body.published() && entity.getPublishedAt() == null) {
                entity.setPublishedAt(Instant.now());
            }
            if (!body.published()) {
                entity.setPublishedAt(null);
            }
        }
        return toDetailResponse(articleRepository.save(entity), categoriesMap());
    }

    @Transactional
    public void deleteArticle(String articleId) {
        ArticleEntity entity = findArticle(articleId);
        articleRepository.delete(entity);
    }

    private ArticleEntity findArticle(String articleId) {
        return articleRepository
                .findById(articleId)
                .or(() -> articleRepository.findBySlug(articleId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Zpráva nenalezena."));
    }

    private ArticleCategoryEntity findCategory(String categoryId) {
        return categoryRepository
                .findById(categoryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Podsekce nenalezena."));
    }

    private String validateCategoryId(String categoryId) {
        if (categoryId == null || categoryId.isBlank()) {
            return null;
        }
        findCategory(categoryId.trim());
        return categoryId.trim();
    }

    private String uniqueArticleSlug(String base, String excludeId) {
        String slug = SlugUtils.slugify(base, "zprava");
        for (int i = 0; i < 2000; i++) {
            String candidate = i == 0 ? slug : slug + "-" + i;
            boolean taken = excludeId == null
                    ? articleRepository.findBySlug(candidate).isPresent()
                    : articleRepository.existsBySlugAndIdNot(candidate, excludeId);
            if (!taken) {
                return candidate;
            }
        }
        return slug + "-x";
    }

    private String uniqueCategorySlug(String base, String excludeId) {
        String slug = SlugUtils.slugify(base, "sekce");
        for (int i = 0; i < 500; i++) {
            String candidate = i == 0 ? slug : slug + "-" + i;
            boolean taken = excludeId == null
                    ? categoryRepository.findBySlug(candidate).isPresent()
                    : categoryRepository.existsBySlugAndIdNot(candidate, excludeId);
            if (!taken) {
                return candidate;
            }
        }
        return slug + "-x";
    }

    private Map<String, ArticleCategoryEntity> categoriesMap() {
        Map<String, ArticleCategoryEntity> map = new HashMap<>();
        for (ArticleCategoryEntity cat : categoryRepository.findAll()) {
            map.put(cat.getId(), cat);
        }
        return map;
    }

    private ArticleCategoryResponse toCategoryResponse(ArticleCategoryEntity entity) {
        return new ArticleCategoryResponse(
                entity.getId(),
                entity.getName(),
                entity.getSlug(),
                entity.getSortOrder(),
                formatInstant(entity.getCreatedAt()),
                formatInstant(entity.getUpdatedAt()));
    }

    private ArticleListResponse toListResponse(ArticleEntity entity, Map<String, ArticleCategoryEntity> categories) {
        CategoryFields cf = categoryFields(entity.getCategoryId(), categories);
        return new ArticleListResponse(
                entity.getId(),
                entity.getSlug() != null ? entity.getSlug() : entity.getId(),
                entity.getTitle(),
                entity.getSummary(),
                blankToNull(entity.getCoverImageUrl()),
                entity.isPublished(),
                formatInstant(entity.getPublishedAt()),
                formatInstant(entity.getCreatedAt()),
                formatInstant(entity.getUpdatedAt()),
                entity.getAuthorName(),
                cf.categoryId(),
                cf.categoryName(),
                cf.categorySlug());
    }

    private ArticleDetailResponse toDetailResponse(ArticleEntity entity, Map<String, ArticleCategoryEntity> categories) {
        CategoryFields cf = categoryFields(entity.getCategoryId(), categories);
        return new ArticleDetailResponse(
                entity.getId(),
                entity.getSlug() != null ? entity.getSlug() : entity.getId(),
                entity.getTitle(),
                entity.getSummary(),
                entity.getBody(),
                blankToNull(entity.getCoverImageUrl()),
                entity.isPublished(),
                formatInstant(entity.getPublishedAt()),
                formatInstant(entity.getCreatedAt()),
                formatInstant(entity.getUpdatedAt()),
                entity.getAuthorName(),
                cf.categoryId(),
                cf.categoryName(),
                cf.categorySlug());
    }

    private CategoryFields categoryFields(String categoryId, Map<String, ArticleCategoryEntity> categories) {
        if (categoryId == null || categoryId.isBlank()) {
            return new CategoryFields(null, null, null);
        }
        ArticleCategoryEntity cat = categories.get(categoryId);
        if (cat != null) {
            return new CategoryFields(categoryId, cat.getName(), cat.getSlug());
        }
        return new CategoryFields(categoryId, null, null);
    }

    private static String formatInstant(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    private record CategoryFields(String categoryId, String categoryName, String categorySlug) {}
}
