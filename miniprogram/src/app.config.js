export default {
  pages: [
    'pages/list/index',
    'pages/detail/index',
    'pages/recommend-all/index',
    'pages/recommend/detail',
    'pages/strategy/index',
    'pages/strategy/detail',
    'pages/factor/index',
    'pages/factor/detail',
    'pages/backtest/index',
    'pages/backtest/detail',
    'pages/mine/index'
  ],
  window: {
    backgroundTextStyle: 'light',
    navigationBarBackgroundColor: '#fff',
    navigationBarTitleText: '量化选股',
    navigationBarTextStyle: 'black',
    backgroundColor: '#f5f5f5'
  },
  tabBar: {
    color: '#9AA1AC',
    selectedColor: '#3B9EFF',
    backgroundColor: '#ffffff',
    borderStyle: 'white',
    list: [
      {
        pagePath: 'pages/list/index',
        text: '行情',
        iconPath: 'assets/tabbar/recommend.png',
        selectedIconPath: 'assets/tabbar/recommend-active.png'
      },
      {
        pagePath: 'pages/strategy/index',
        text: '策略',
        iconPath: 'assets/tabbar/strategy.png',
        selectedIconPath: 'assets/tabbar/strategy-active.png'
      },
      {
        pagePath: 'pages/backtest/index',
        text: '回测',
        iconPath: 'assets/tabbar/backtest.png',
        selectedIconPath: 'assets/tabbar/backtest-active.png'
      },
      {
        pagePath: 'pages/factor/index',
        text: '因子库',
        iconPath: 'assets/tabbar/factor.png',
        selectedIconPath: 'assets/tabbar/factor-active.png'
      },
      {
        pagePath: 'pages/mine/index',
        text: '关于',
        iconPath: 'assets/tabbar/mine.png',
        selectedIconPath: 'assets/tabbar/mine-active.png'
      }
    ]
  }
};
