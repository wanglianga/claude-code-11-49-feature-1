package com.clinic.mpi.service;

/** 审计接口角色：通过请求头 X-Operator-Role 传递。 */
public final class AuditRoles {

    /** 经授权人工核验员：抽样工作台、审核标注、复核裁决 */
    public static final String REVIEWER = "REVIEWER";
    /** 数据治理管理员：抽样编排、阈值发布、复核裁决 */
    public static final String ADMIN = "ADMIN";
    /** 普通运营用户：只能读聚合质量报告，不可读审核个案明细 */
    public static final String OPS = "OPS";

    private AuditRoles() {
    }

    public static boolean canReview(String role) {
        return REVIEWER.equals(role) || ADMIN.equals(role);
    }

    public static boolean isAdmin(String role) {
        return ADMIN.equals(role);
    }
}
