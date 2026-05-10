package com.zhiguang.be.profile.mapper;

import com.zhiguang.be.profile.model.ProfileUserRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 个人资料模块 Mapper。
 * 负责用户资料读取和局部更新。
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
            @Param("tagsJson") String tagsJson,
            @Param("clearBio") boolean clearBio,
            @Param("clearBirthday") boolean clearBirthday,
            @Param("clearSchool") boolean clearSchool
    );
}
