package com.clinic.mpi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 连锁诊所就诊人主索引（MPI）合并拆分与快照稳定服务。
 *
 * <p>核心设计：时态关系图（mpi_link 带生效版本区间）+ 业务快照固定引用图谱版本。
 * 下游（预约提醒、结算、检验、经营分析）只读取已发布快照，身份更正只影响新快照
 * 与明确列出的下游影响清单，已关账凭证永不被改写。</p>
 */
@SpringBootApplication
public class MpiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MpiApplication.class, args);
    }
}
