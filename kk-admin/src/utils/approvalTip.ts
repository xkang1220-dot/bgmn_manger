/** 从审批提交结果或配置预览生成提示文案 */
export function approvalFlowTip(res: any, fallback = '已提交审批，请到审批中心查看进度') {
  if (res?.flowTip) return String(res.flowTip)
  if (res?.tip) return String(res.tip)
  return fallback
}
