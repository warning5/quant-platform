import { View, Text } from '@tarojs/components';
import './index.scss';

export default function AboutPage() {
  return (
    <View className='about-page'>
      <View className='about-card card'>
        <Text className='app-name'>量化选股</Text>
        <Text className='app-version'>v1.0.0</Text>
      </View>

      <View className='feature-card card'>
        <Text className='section-title'>功能特点</Text>
        <View className='feature-item'>
          <Text className='feature-dot'>●</Text>
          <Text className='feature-text'>多策略智能选股，因子+分析双引擎</Text>
        </View>
        <View className='feature-item'>
          <Text className='feature-dot'>●</Text>
          <Text className='feature-text'>交易计划（买入价/止损/止盈/仓位）</Text>
        </View>
        <View className='feature-item'>
          <Text className='feature-dot'>●</Text>
          <Text className='feature-text'>多维度评分（技术/资金/基本面/事件）</Text>
        </View>
        <View className='feature-item'>
          <Text className='feature-dot'>●</Text>
          <Text className='feature-text'>策略置信度动态调整推荐数量</Text>
        </View>
        <View className='feature-item'>
          <Text className='feature-dot'>●</Text>
          <Text className='feature-text'>大盘指数实时行情</Text>
        </View>
        <View className='feature-item'>
          <Text className='feature-dot'>●</Text>
          <Text className='feature-text'>历史批次表现追踪与复盘</Text>
        </View>
      </View>

      <View className='disclaimer-card card'>
        <Text className='section-title'>风险提示</Text>
        <Text className='disclaimer-text'>
          本应用提供的所有信息仅供参考，不构成任何投资建议。股市有风险，投资需谨慎。请根据自身风险承受能力做出独立判断。
        </Text>
      </View>
    </View>
  );
}
