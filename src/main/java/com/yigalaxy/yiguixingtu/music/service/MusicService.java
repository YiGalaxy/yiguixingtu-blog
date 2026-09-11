package com.yigalaxy.yiguixingtu.music.service;

import com.yigalaxy.yiguixingtu.music.dto.MusicForm;
import com.yigalaxy.yiguixingtu.music.dto.MusicVO;

import java.util.List;

/**
 * 音乐服务。
 *
 * 【与 F5 四个内容模块完全同形】公开只读 listVisible（走缓存、只含 status = 1）
 * + 后台 listAll（含隐藏、不走缓存）+ create / update / delete（类级 ADMIN）。
 * 唯一多出来的一件事是"音频文件怎么来的" —— 那是上传接口的职责（见 upload 包），
 * 这个模块只保存"上传回来的那个地址"，不碰文件本身。
 */
public interface MusicService {

    /** 前台音乐列表：只返回 status = 1，按 sort 升序、id 升序；走 Redis 缓存 */
    List<MusicVO> listVisible();

    /** 后台音乐列表：含隐藏的，按 sort 升序、id 升序；不走缓存 */
    List<MusicVO> listAll();

    /** 新建音乐，返回新音乐 id */
    Long create(MusicForm form);

    /** 编辑音乐（曲名 / 歌手 / 音频地址 / 封面 / 歌词 / 排序 / 显示状态） */
    void update(Long id, MusicForm form);

    /** 删除音乐（逻辑删除；磁盘上的音频文件【不】删，理由见 Music 实体注释） */
    void delete(Long id);
}
