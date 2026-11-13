import { createContext, type ComponentType } from 'react'

// 수리 상세 '더 보기' 메뉴에 넣을 행동(화면 원칙 1: 크게 보이는 행동 버튼은 최대 두 개, 나머지는 메뉴로)
export interface MoreItem { key: string; label: string; icon?: ComponentType<{ size?: number; 'aria-hidden'?: boolean }>; onSelect: () => void }
// 수리 상세가 이 함수를 내려 주면, 상태 버튼(RepairActions)이 두 개를 넘는 행동을 여기에 등록해요. 없으면 버튼을 모두 그대로 보여 줘요.
export const DetailMoreContext = createContext<((items: MoreItem[]) => void) | null>(null)
