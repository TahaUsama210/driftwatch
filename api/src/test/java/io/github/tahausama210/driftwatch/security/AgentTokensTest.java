package io.github.tahausama210.driftwatch.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AgentTokensTest {

    @Test
    void tokensArePrefixedUnpaddedBase64UrlOf32RandomBytes() {
        String token = AgentTokens.generate();

        assertThat(token).startsWith("dwa_").hasSize(4 + 43).doesNotContain("=", "+", "/");
        assertThat(Base64.getUrlDecoder().decode(token.substring(4))).hasSize(32);
        assertThat(AgentTokens.hasValidFormat(token)).isTrue();
    }

    @Test
    void tokensDoNotRepeat() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            tokens.add(AgentTokens.generate());
        }
        assertThat(tokens).hasSize(1_000);
    }

    @Test
    void hashIsLowerCaseHexSha256() {
        // FIPS 180-2 test vector for "abc".
        assertThat(AgentTokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void hashIsDeterministicAndDistinguishesTokens() {
        String token = AgentTokens.generate();

        assertThat(AgentTokens.hash(token)).isEqualTo(AgentTokens.hash(token)).hasSize(64);
        assertThat(AgentTokens.hash(token)).isNotEqualTo(AgentTokens.hash(AgentTokens.generate()));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "dwa_",
                "dwa_tooShort",
                "xyz_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "dwa_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "dwa_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            })
    void stringsThatCannotBeTokensAreRecognised(String candidate) {
        assertThat(AgentTokens.hasValidFormat(candidate)).isFalse();
    }
}
