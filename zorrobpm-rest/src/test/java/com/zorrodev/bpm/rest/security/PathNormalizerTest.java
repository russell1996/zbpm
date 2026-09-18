package com.zorrodev.bpm.rest.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PathNormalizer}.
 *
 * WO-BE-4: path traversal resolution ({@code ..}, {@code .}).
 * WO-SEC-10/15: existing matrix-param, double-slash, %-encoding normalization.
 */
class PathNormalizerTest {

    // ========== WO-BE-4: path traversal (.. and .) resolution ==========

    @Test
    void normalPath_unchanged() {
        assertThat(PathNormalizer.normalize("/users")).isEqualTo("/users");
    }

    @Test
    void normalPathWithId_unchanged() {
        assertThat(PathNormalizer.normalize("/users/123")).isEqualTo("/users/123");
    }

    @Test
    void normalAuthPath_unchanged() {
        assertThat(PathNormalizer.normalize("/auth/login")).isEqualTo("/auth/login");
    }

    @Test
    void singleDot_removed() {
        assertThat(PathNormalizer.normalize("/users/./123")).isEqualTo("/users/123");
    }

    @Test
    void singleDotAtRoot_removed() {
        assertThat(PathNormalizer.normalize("/./auth/login")).isEqualTo("/auth/login");
    }

    @Test
    void singleDotAtEnd_removed() {
        assertThat(PathNormalizer.normalize("/users/.")).isEqualTo("/users");
    }

    @Test
    void parentTraversal_authToUsers_resolved() {
        // /auth/../users/123 → /users/123
        assertThat(PathNormalizer.normalize("/auth/../users/123")).isEqualTo("/users/123");
    }

    @Test
    void parentTraversal_cyclic_resolved() {
        // /users/../users/123 → /users/123
        assertThat(PathNormalizer.normalize("/users/../users/123")).isEqualTo("/users/123");
    }

    @Test
    void parentTraversal_multiLevel_resolved() {
        // /a/b/c/../../d → /a/d
        assertThat(PathNormalizer.normalize("/a/b/c/../../d")).isEqualTo("/a/d");
    }

    @Test
    void parentTraversal_aboveRoot_clamped() {
        // /../../etc/passwd → /etc/passwd (can't go above root)
        assertThat(PathNormalizer.normalize("/../../etc/passwd")).isEqualTo("/etc/passwd");
    }

    @Test
    void parentTraversal_onlyDoubleDots_returnsRoot() {
        assertThat(PathNormalizer.normalize("/../..")).isEqualTo("/");
    }

    @Test
    void parentTraversal_emptyPath_returnsRoot() {
        assertThat(PathNormalizer.normalize("..")).isEqualTo("/");
    }

    // ========== Combined with existing normalizations ==========

    @Test
    void encodedAndTraversal_resolved() {
        // /%61uth/../users/123 → /auth/../users/123 after decode → /users/123
        assertThat(PathNormalizer.normalize("/%61uth/../users/123")).isEqualTo("/users/123");
    }

    @Test
    void doubleSlashAndTraversal_resolved() {
        // //auth//../users/ → /users after collapse + resolve + strip trailing slash
        assertThat(PathNormalizer.normalize("//auth//../users/")).isEqualTo("/users");
    }

    @Test
    void matrixParamAndTraversal_resolved() {
        // /users;x=1/../foo → matrix strip gives /users/../foo → resolves to /foo
        assertThat(PathNormalizer.normalize("/users;x=1/../foo")).isEqualTo("/foo");
    }

    // ========== Edge cases ==========

    @Test
    void null_returnsRoot() {
        assertThat(PathNormalizer.normalize(null)).isEqualTo("/");
    }

    @Test
    void root_unchanged() {
        assertThat(PathNormalizer.normalize("/")).isEqualTo("/");
    }

    @Test
    void singleDot_returnsRoot() {
        assertThat(PathNormalizer.normalize(".")).isEqualTo("/");
    }

    @Test
    void dotSlashDotDot_returnsRoot() {
        assertThat(PathNormalizer.normalize("/./..")).isEqualTo("/");
    }

    @Test
    void resolveDotSegments_simplePath() {
        assertThat(PathNormalizer.resolveDotSegments("/users")).isEqualTo("/users");
    }

    @Test
    void resolveDotSegments_singleDot() {
        assertThat(PathNormalizer.resolveDotSegments("/users/./123")).isEqualTo("/users/123");
    }

    @Test
    void resolveDotSegments_parentTraversal() {
        assertThat(PathNormalizer.resolveDotSegments("/a/b/../c")).isEqualTo("/a/c");
    }

    @Test
    void resolveDotSegments_aboveRoot() {
        assertThat(PathNormalizer.resolveDotSegments("/../../x")).isEqualTo("/x");
    }

    @Test
    void resolveDotSegments_onlyDots() {
        assertThat(PathNormalizer.resolveDotSegments("../../")).isEqualTo("/");
    }

    @Test
    void resolveDotSegments_relativePath() {
        assertThat(PathNormalizer.resolveDotSegments("a/b/c")).isEqualTo("a/b/c");
    }

    @Test
    void resolveDotSegments_relativeWithDots() {
        assertThat(PathNormalizer.resolveDotSegments("a/./b/../c")).isEqualTo("a/c");
    }

    // ========== Existing normalizations (regression) ==========

    @Test
    void matrixParam_stillStripped() {
        assertThat(PathNormalizer.normalize("/users;x=1")).isEqualTo("/users");
    }

    @Test
    void doubleSlash_stillCollapsed() {
        assertThat(PathNormalizer.normalize("//users")).isEqualTo("/users");
    }

    @Test
    void percentEncoding_stillDecoded() {
        assertThat(PathNormalizer.normalize("/%75sers")).isEqualTo("/users");
    }

    @Test
    void trailingSlash_stillStripped() {
        assertThat(PathNormalizer.normalize("/users/")).isEqualTo("/users");
    }

    @Test
    void encodedDoubleDot_resolved() {
        // /%2e%2e/users → /../users after decode → /users after resolve
        assertThat(PathNormalizer.normalize("/%2e%2e/users")).isEqualTo("/users");
    }

    @Test
    void encodedSlashTraversal_resolved() {
        // /auth%2f%2e%2e%2fusers → /auth/../users after decode → /users
        assertThat(PathNormalizer.normalize("/auth%2f%2e%2e%2fusers")).isEqualTo("/users");
    }

    @Test
    void mixedEncoding_resolved() {
        // /%61uth/%2e%2e/%75sers → /auth/../users after decode → /users
        assertThat(PathNormalizer.normalize("/%61uth/%2e%2e/%75sers")).isEqualTo("/users");
    }
}
