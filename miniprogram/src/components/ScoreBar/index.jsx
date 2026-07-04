import { View } from '@tarojs/components';
import './ScoreBar.scss';

/**
 * 评分进度条组件
 * @param {string} label - 标签
 * @param {number} score - 分数 0~100
 * @param {string} color - 颜色 red/orange/green/purple
 */
export default function ScoreBar({ label, score, color = 'red' }) {
  const width = Math.min(Math.max(score || 0, 0), 100);
  return (
    <View className='score-bar'>
      <View className='bar-label'>{label}</View>
      <View className='bar-track'>
        <View
          className={`bar-fill bar-${color}`}
          style={{ width: width + '%' }}
        />
      </View>
      <View className='bar-value'>{score != null ? score : '--'}</View>
    </View>
  );
}
