package com.felix.miraagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.felix.miraagent.persistence.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.Instant;

@Data
@TableName(value = "skills", autoResultMap = true)
public class SkillEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String name;
    private String description;
    private String status;

    @TableField(value = "tags", typeHandler = JsonbTypeHandler.class)
    private String tags;

    private Boolean pinned;
    private Integer useCount;
    private Integer version;
    private String sourceUri;
    private String sourceTraceId;
    private String sourceSessionId;
    private String embeddingRef;
    private Instant lastUsedAt;
    private Instant archivedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
