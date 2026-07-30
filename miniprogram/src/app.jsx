import { Component } from 'react';
import './app.scss';
import { ensureLogin } from './utils/request';

class App extends Component {
  componentDidMount() {
    // 预热微信登录：拿到用户 token 写入本地，后续请求自动携带。
    // 失败不阻塞首屏（请求层会在 401 时自动补登并重试）。
    ensureLogin().catch(() => {});
  }

  componentDidShow() {}

  componentDidHide() {}

  componentDidCatchError() {}

  render() {
    return this.props.children;
  }
}

export default App;
