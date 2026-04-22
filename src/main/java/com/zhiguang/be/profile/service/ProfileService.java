package com.zhiguang.be.profile.service;

import com.zhiguang.be.content.dto.PostPageData;
import com.zhiguang.be.profile.model.ProfileAvatarRequest;
import com.zhiguang.be.profile.model.ProfileData;
import com.zhiguang.be.profile.model.ProfileListData;
import com.zhiguang.be.profile.model.ProfilePatchRequest;

/**
 * 个人资料服务接口。
 * 对外提供资料查询、资料编辑和个人主页聚合能力。
 */
public interface ProfileService {

    /**
     * 查询当前登录用户的个人资料。
     *
     * @param currentUserId 当前登录用户 ID
     * @return 个人资料聚合结果
     */
    ProfileData me(long currentUserId);

    /**
     * 查询指定用户主页资料。
     *
     * @param viewerUserId 当前查看者 ID，匿名时可传 0
     * @param targetUserId 目标用户 ID
     * @return 个人资料聚合结果
     */
    ProfileData getProfile(long viewerUserId, long targetUserId);

    /**
     * 局部更新当前用户资料。
     *
     * @param currentUserId 当前登录用户 ID
     * @param request 更新请求
     * @return 更新后的个人资料
     */
    ProfileData updateProfile(long currentUserId, ProfilePatchRequest request);

    /**
     * 单独更新当前用户头像。
     *
     * @param currentUserId 当前登录用户 ID
     * @param request 头像更新请求
     * @return 更新后的个人资料
     */
    ProfileData updateAvatar(long currentUserId, ProfileAvatarRequest request);

    /**
     * 查询指定用户的关注列表资料视图。
     *
     * @param viewerUserId 当前查看者 ID，匿名时可传 0
     * @param targetUserId 目标用户 ID
     * @param page 页码
     * @param size 每页大小
     * @return 关注列表
     */
    ProfileListData getFollowingProfiles(long viewerUserId, long targetUserId, int page, int size);

    /**
     * 查询指定用户的粉丝列表资料视图。
     *
     * @param viewerUserId 当前查看者 ID，匿名时可传 0
     * @param targetUserId 目标用户 ID
     * @param page 页码
     * @param size 每页大小
     * @return 粉丝列表
     */
    ProfileListData getFollowerProfiles(long viewerUserId, long targetUserId, int page, int size);

    /**
     * 查询指定用户主页可见的已发布内容。
     *
     * @param viewerUserId 当前查看者 ID，匿名时可传 0
     * @param targetUserId 目标用户 ID
     * @param page 页码
     * @param size 每页大小
     * @return 内容分页结果
     */
    PostPageData getPublishedPosts(long viewerUserId, long targetUserId, int page, int size);
}
