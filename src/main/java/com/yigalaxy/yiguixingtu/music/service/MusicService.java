package com.yigalaxy.yiguixingtu.music.service;

import com.yigalaxy.yiguixingtu.music.dto.MusicForm;
import com.yigalaxy.yiguixingtu.music.dto.MusicVO;

import java.util.List;

/**
 * 音乐服务。
 *
 * 【与 F5 四个内容模块完全同形】公开只读 listVisible（走缓存、只含 status = 1）
 * + 后台 listAll（含隐藏、不走缓存）+ create / update / delete（类级 ADMIN）。
 * 唯一多出来的一件事是"音频文件怎么来的、删除时怎么清" ——
 * 上传由上传接口负责（见 upload 包），这个模块只保存"上传回来的那个地址"，
 * 删除时按地址判断"这个文件还有没有别人在用"再决定清不清。
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

    /**
     * 删除音乐（逻辑删除数据库行；磁盘上的音频文件在确认"没有别人引用"后一并删掉）。
     *
     * 【为什么是"确认后删"而不是"一律删"或"一律不删"】见 MusicServiceImpl.delete 的长注释：
     *   同一份音频可能被别的曲目、文章正文/封面、甚至某条文章附件用到，
     *   而删文件不可逆 —— 所以先用一条 COUNT 问清三处，再决定；
     *   url 是 http(s) 外链时不属于本项目的上传目录，一个字节都不碰。
     */
    void delete(Long id);
}
