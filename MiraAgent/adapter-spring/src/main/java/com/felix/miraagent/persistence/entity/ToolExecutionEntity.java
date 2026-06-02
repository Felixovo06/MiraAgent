package com.felix.miraagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName(value = "tool_executions", autoResultMap = true)
public class ToolExecutionEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    private String runId;
    private String sessionId;
    private String toolCallId;
    private String toolName;
    private String arguments;
    private String status;
    private String modelVisibleContent;
    private String errorMessage;
    private Instant startedAt;
    private Instant finishedAt;
}
