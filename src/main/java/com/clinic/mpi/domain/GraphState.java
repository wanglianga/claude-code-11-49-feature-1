package com.clinic.mpi.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 全局图谱状态（单行）：当前已发布的图谱版本号。 */
@Entity
@Table(name = "graph_state")
public class GraphState {

    public static final int SINGLETON_ID = 1;

    @Id
    public int id = SINGLETON_ID;

    @Column(nullable = false)
    public long version = 0L;
}
