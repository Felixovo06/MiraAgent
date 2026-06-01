package com.felix.miraagent.skill;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.felix.miraagent.persistence.mapper.SkillMapper;
import com.felix.miraagent.persistence.mybatis.MybatisSkillIndexRepository;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MybatisSkillIndexRepositoryTest {

    private MybatisSkillIndexRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:skills_mp;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        new JdbcTemplate(dataSource).execute("drop table if exists skills");
        new JdbcTemplate(dataSource).execute("""
                create table if not exists skills (
                    id text primary key,
                    name text not null,
                    description text,
                    status text not null default 'ACTIVE',
                    tags varchar,
                    pinned boolean not null default false,
                    use_count integer not null default 0,
                    version integer not null default 1,
                    source_uri text,
                    source_trace_id text,
                    source_session_id text,
                    embedding_ref text,
                    last_used_at timestamp with time zone,
                    archived_at timestamp with time zone,
                    created_at timestamp with time zone not null,
                    updated_at timestamp with time zone not null
                )
                """);
        repo = new MybatisSkillIndexRepository(mapper(dataSource), new ObjectMapper());
    }

    private SkillMapper mapper(DataSource dataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionFactory sqlSessionFactory = factory.getObject();
        sqlSessionFactory.getConfiguration().addMapper(SkillMapper.class);
        SqlSession sqlSession = sqlSessionFactory.openSession(true);
        return sqlSession.getMapper(SkillMapper.class);
    }

    private SkillIndex index(String id, SkillStatus status) {
        Instant now = Instant.now();
        return SkillIndex.builder()
                .skillId(id)
                .name(id)
                .description("desc-" + id)
                .status(status)
                .tags(List.of("a", "b"))
                .pinned(false)
                .useCount(0)
                .version(1)
                .sourceUri("skills/" + id + "/SKILL.md")
                .sourceTraceId("trace-1")
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    @Test
    void saveAndFindById() {
        repo.save(index("code-review", SkillStatus.ACTIVE));
        var found = repo.findById("code-review");
        assertTrue(found.isPresent());
        assertEquals("desc-code-review", found.get().getDescription());
        assertEquals(List.of("a", "b"), found.get().getTags());
        assertEquals("trace-1", found.get().getSourceTraceId());
    }

    @Test
    void findActiveExcludesArchived() {
        repo.save(index("active-skill", SkillStatus.ACTIVE));
        repo.save(index("to-archive", SkillStatus.ACTIVE));
        repo.archive("to-archive");

        List<SkillIndex> active = repo.findActive();
        assertEquals(1, active.size());
        assertEquals("active-skill", active.get(0).getSkillId());
    }

    @Test
    void saveUpsertsOnSameId() {
        repo.save(index("code-review", SkillStatus.ACTIVE));
        repo.save(index("code-review", SkillStatus.ACTIVE));
        assertEquals(1, repo.findActive().size());
    }

    @Test
    void deleteAllClearsIndex() {
        repo.save(index("a", SkillStatus.ACTIVE));
        repo.save(index("b", SkillStatus.ACTIVE));
        repo.deleteAll();
        assertTrue(repo.findActive().isEmpty());
    }
}
