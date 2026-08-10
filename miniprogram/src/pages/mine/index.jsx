import { useState, useEffect, useCallback } from 'react';
import { View, Text, Input } from '@tarojs/components';
import Taro from '@tarojs/taro';
import { profileApi } from '../../api';
import { formatDate } from '../../utils/format';
import './index.scss';

const TOKEN_KEY = 'mp_user_token';

export default function MinePage() {
  const [profile, setProfile] = useState(null);
  const [nickname, setNickname] = useState('');
  const [saving, setSaving] = useState(false);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await profileApi.get();
      setProfile(data);
      setNickname(data.nickname || '');
    } catch (e) {
      console.error('加载资料失败', e);
      setProfile(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const save = async () => {
    if (saving) return;
    setSaving(true);
    try {
      const data = await profileApi.update({ nickname });
      setProfile(data);
      Taro.showToast({ title: '已保存', icon: 'success' });
    } catch (e) {
      Taro.showToast({ title: '保存失败', icon: 'none' });
    } finally {
      setSaving(false);
    }
  };

  const logout = () => {
    Taro.showModal({
      title: '退出登录',
      content: '确定要退出当前账号吗？',
      success: (res) => {
        if (res.confirm) {
          Taro.removeStorageSync(TOKEN_KEY);
          Taro.reLaunch({ url: '/pages/list/index' });
        }
      }
    });
  };

  return (
    <View className='mine-page'>
      {loading ? (
        <View className='empty-state'><Text className='empty-text'>加载中...</Text></View>
      ) : !profile ? (
        <View className='empty-state'><Text className='empty-text'>未登录</Text></View>
      ) : (
        <View>
          {/* 资料卡片 */}
          <View className='section card'>
            <View className='avatar'>{nickname ? nickname.charAt(0) : '微'}</View>
            <View className='profile-name'>{nickname || '微信用户'}</View>
            {profile.phone && <Text className='profile-sub'>{profile.phone}</Text>}

            <View className='edit-row'>
              <Text className='edit-label'>昵称</Text>
              <Input
                className='edit-input'
                value={nickname}
                onInput={(e) => setNickname(e.detail.value)}
                placeholder='请输入昵称'
              />
            </View>
            <View className='profile-row'>
              <Text className='profile-label'>邮箱</Text>
              <Text className='profile-value'>{profile.email || '未设置'}</Text>
            </View>
            <View className='profile-row'>
              <Text className='profile-label'>状态</Text>
              <Text className='profile-value'>{profile.status === 1 ? '正常' : '禁用'}</Text>
            </View>
            {profile.lastLoginTime && (
              <View className='profile-row'>
                <Text className='profile-label'>上次登录</Text>
                <Text className='profile-value'>{formatDate(profile.lastLoginTime)}</Text>
              </View>
            )}

            <View className='save-btn' onClick={save}>
              <Text className='save-text'>{saving ? '保存中...' : '保存资料'}</Text>
            </View>
          </View>

          {/* 风险提示 */}
          <View className='section card'>
            <Text className='section-title'>风险提示</Text>
            <Text className='disclaimer-text'>
              本应用提供的所有信息仅供参考，不构成任何投资建议。股市有风险，投资需谨慎。请根据自身风险承受能力做出独立判断。
            </Text>
          </View>

          {/* 退出登录 */}
          <View className='logout-btn' onClick={logout}>
            <Text className='logout-text'>退出登录</Text>
          </View>
        </View>
      )}
    </View>
  );
}
