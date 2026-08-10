import { View, Text } from '@tarojs/components';
import './index.scss';

export default function BacktestPage() {
  return (
    <View className='bt-page'>
      <View className='bt-head'>回测</View>
      <View className='bt-body'>
        <View className='bt-empty'>
          <Text className='bt-empty-text'>回测模块建设中</Text>
          <Text className='bt-empty-sub'>支持策略历史收益、最大回撤、夏普比率等指标回测</Text>
        </View>
      </View>
    </View>
  );
}
