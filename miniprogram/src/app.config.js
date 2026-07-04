export default {
  pages: [
    'pages/list/index',
    'pages/detail/index',
    'pages/history/index',
    'pages/about/index'
  ],
  window: {
    backgroundTextStyle: 'light',
    navigationBarBackgroundColor: '#fff',
    navigationBarTitleText: '量化选股',
    navigationBarTextStyle: 'black',
    backgroundColor: '#f5f5f5'
  },
  tabBar: {
    color: '#999999',
    selectedColor: '#c0392b',
    backgroundColor: '#ffffff',
    borderStyle: 'white',
    list: [
      {
        pagePath: 'pages/list/index',
        text: '推荐',
        iconPath: 'assets/tabbar/recommend.png',
        selectedIconPath: 'assets/tabbar/recommend-active.png'
      },
      {
        pagePath: 'pages/history/index',
        text: '表现',
        iconPath: 'assets/tabbar/performance.png',
        selectedIconPath: 'assets/tabbar/performance-active.png'
      },
      {
        pagePath: 'pages/about/index',
        text: '关于',
        iconPath: 'assets/tabbar/about.png',
        selectedIconPath: 'assets/tabbar/about-active.png'
      }
    ]
  }
};
