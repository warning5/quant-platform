import { View, Text } from '@tarojs/components';
import Taro from '@tarojs/taro';
import './index.scss';

export default function MinePage() {
  const goStrategy = () => Taro.switchTab({ url: '/pages/strategy/index' });
  const goBacktest = () => Taro.switchTab({ url: '/pages/backtest/index' });
  const toast = (msg) => Taro.showToast({ title: msg, icon: 'none' });

  return (
    <View className='mine-page'>
      {/* 设置 */}
      <View className='set-row'>
        <Text className='section-t' style={{ margin: 0 }}>设置</Text>
        <View className='btn ghost' onClick={() => toast('设置功能开发中')}>进入</View>
      </View>

      {/* 功能入口 */}
      <View className='card' style={{ paddingTop: 4 }}>
        <View className='row' onClick={goStrategy}>
          <Text className='lbl'>我的策略</Text>
          <svg width='16' height='16' viewBox='0 0 16 16' fill='none'>
            <path d='M6 4l4 4-4 4' stroke='#9AA1AC' strokeWidth='1.8' strokeLinecap='round' strokeLinejoin='round' />
          </svg>
        </View>
        <View className='row' onClick={goBacktest}>
          <Text className='lbl'>回测记录</Text>
          <svg width='16' height='16' viewBox='0 0 16 16' fill='none'>
            <path d='M6 4l4 4-4 4' stroke='#9AA1AC' strokeWidth='1.8' strokeLinecap='round' strokeLinejoin='round' />
          </svg>
        </View>
        <View className='row' onClick={() => toast('帮助中心开发中')}>
          <Text className='lbl'>帮助中心</Text>
          <svg width='16' height='16' viewBox='0 0 16 16' fill='none'>
            <path d='M6 4l4 4-4 4' stroke='#9AA1AC' strokeWidth='1.8' strokeLinecap='round' strokeLinejoin='round' />
          </svg>
        </View>
        <View className='row' onClick={() => toast('量策 v1.0 · 智能投顾')}>
          <Text className='lbl'>关于量策</Text>
          <svg width='16' height='16' viewBox='0 0 16 16' fill='none'>
            <path d='M6 4l4 4-4 4' stroke='#9AA1AC' strokeWidth='1.8' strokeLinecap='round' strokeLinejoin='round' />
          </svg>
        </View>
      </View>
    </View>
  );
}
