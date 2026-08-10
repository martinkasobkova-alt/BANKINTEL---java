package cz.bankintel.controller.content;

import cz.bankintel.domain.dto.ContentDtos;
import cz.bankintel.domain.dto.ContentDtos.ArticleCategoriesReorderBody;
import cz.bankintel.domain.dto.ContentDtos.ArticleCategoryCreateRequest;
import cz.bankintel.domain.dto.ContentDtos.ArticleCategoryUpdateRequest;
import cz.bankintel.domain.dto.ContentDtos.ArticleCreateRequest;
import cz.bankintel.domain.dto.ContentDtos.ArticleUpdateRequest;
import cz.bankintel.security.CurrentUser;
import cz.bankintel.service.content.ArticleService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/articles")
@RequiredArgsConstructor
public class ArticlesController {

    private final ArticleService articleService;
    private final CurrentUser currentUser;

    @GetMapping("/categories")
    public List<ContentDtos.ArticleCategoryResponse> listCategories() {
        return articleService.listCategories();
    }

    @PostMapping("/categories")
    public ContentDtos.ArticleCategoryResponse createCategory(
            @Valid @RequestBody ArticleCategoryCreateRequest body) {
        currentUser.requireEditor();
        return articleService.createCategory(body);
    }

    @PatchMapping("/categories/{categoryId}")
    public ContentDtos.ArticleCategoryResponse updateCategory(
            @PathVariable String categoryId, @Valid @RequestBody ArticleCategoryUpdateRequest body) {
        currentUser.requireEditor();
        return articleService.updateCategory(categoryId, body);
    }

    @DeleteMapping("/categories/{categoryId}")
    public Map<String, Boolean> deleteCategory(@PathVariable String categoryId) {
        currentUser.requireEditor();
        articleService.deleteCategory(categoryId);
        return ContentDtos.okMap();
    }

    @PostMapping("/categories/reorder")
    public Map<String, Boolean> reorderCategories(@Valid @RequestBody ArticleCategoriesReorderBody body) {
        currentUser.requireEditor();
        articleService.reorderCategories(body);
        return ContentDtos.okMap();
    }

    @GetMapping({"", "/"})
    public List<ContentDtos.ArticleListResponse> listArticles(
            @RequestParam(defaultValue = "40") int limit,
            @RequestParam(defaultValue = "0") int skip,
            @RequestParam(required = false) String q,
            @RequestParam(name = "category_id", required = false) String categoryId) {
        return articleService.listArticles(currentUser.optionalUserEntity(), limit, skip, q, categoryId);
    }

    @GetMapping("/{articleId}")
    public ContentDtos.ArticleDetailResponse getArticle(@PathVariable String articleId) {
        return articleService.getArticle(articleId, currentUser.optionalUserEntity());
    }

    @PostMapping({"", "/"})
    public ContentDtos.ArticleDetailResponse createArticle(@Valid @RequestBody ArticleCreateRequest body) {
        return articleService.createArticle(body, currentUser.requireEditor());
    }

    @PatchMapping("/{articleId}")
    public ContentDtos.ArticleDetailResponse updateArticle(
            @PathVariable String articleId, @Valid @RequestBody ArticleUpdateRequest body) {
        currentUser.requireEditor();
        return articleService.updateArticle(articleId, body);
    }

    @DeleteMapping("/{articleId}")
    public Map<String, Boolean> deleteArticle(@PathVariable String articleId) {
        currentUser.requireEditor();
        articleService.deleteArticle(articleId);
        return ContentDtos.okMap();
    }
}
