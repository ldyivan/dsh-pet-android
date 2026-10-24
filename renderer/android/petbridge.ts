// Android 移植：替代 src/client/settings.ts 中的 petBridge 单例。
// pet.ts 只从这里取 petBridge（配置容器 ↔ 设置页之间的桥），settings.ts 的 React 组件
// 依赖 DSH 平台 primitives，不参与 Android 渲染层打包，因此单独提供同形状对象。
import type { Pet } from '../src/shared/types';

export const petBridge: {
  current: Pet[];
  reload: (merged?: Record<string, Record<string, unknown>>) => void;
  template: Pet | undefined;
  /** 宠物被真正点击（非拖拽）时回调：Android 侧用来播点击音效 + 弹一句随机台词。 */
  clickFeedback?: (petId: string) => void;
} = {
  current: [],
  reload: () => {},
  template: undefined,
};

/** 设置页命名空间（与原实现同值，供将来复用设置组件时对齐） */
export const NS = 'dsh-pet';
