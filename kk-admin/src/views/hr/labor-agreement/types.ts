/** 劳务协议数据类型 */

export interface LaborPartyA {
  name: string
  creditCode: string
  legalRep: string
  address: string
}

export interface LaborPartyB {
  name: string
  idNumber: string
  address: string
  bankAccountName: string
  bankName: string
  bankBranch: string
  bankAccount: string
}

export interface LaborClause {
  id: string
  title: string
  enabled: boolean
  content: string
}

export interface LaborContractData {
  meta: {
    type: 'employment'
    title: string
    titleEn: string
  }
  partyA: LaborPartyA
  partyB: LaborPartyB
  projectName: string
  effectiveDate: string
  endDate: string
  signDate: string
  serviceMonths: string
  amountInWords: string
  invoiceBy: string
  refNumber: string
  payment: {
    total: string | number
    payDays: string
  }
  clauses: LaborClause[]
  /** 选中的人员档案 id（仅前端） */
  archiveId?: number | null
  /** 选中的甲方公司（一级部门）id */
  companyId?: number | null
}
