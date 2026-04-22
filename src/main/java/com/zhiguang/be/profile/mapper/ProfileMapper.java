package com.zhiguang.be.profile.mapper;

import com.zhiguang.be.profile.model.ProfileUserRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 个人模块 Mapper。
 * 承担用户资料读取与局部更新能力。
 */
@Mapper
public interface ProfileMapper {

    /**
     * 按用户 ID 查询个人资料。
     */
    ProfileUserRow findByUserId(@Param("userId") long userId);

    /**
     * 批量按用户 ID 查询个人资料。
     */
    List<ProfileUserRow> listByUserIds(@Param("userIds") List<Long> userIds);

    /**
     * 局部更新用户资料。
     */
    int updateProfile(
            @Param("userId") long userId,
            @Param("nickname") String nickname,
            @Param("avatar") String avatar,
            @Param("bio") String bio,
            @Param("gender") String gender,
            @Param("birthday") java.time.LocalDate birthday,
            @Param("school") String school,
            @Param("tagsJson") String tagsJson
    );
}
