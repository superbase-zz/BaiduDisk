package com.csh.http;


import cn.hutool.core.codec.Base64;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.CharsetUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.script.JavaScriptEngine;
import cn.hutool.script.ScriptUtil;
import com.csh.coustom.dialog.MessageDialog;
import com.csh.model.BaiduFile;
import com.csh.utils.Constant;
import com.csh.utils.CookieUtil;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.scene.image.Image;
import org.apache.log4j.Logger;

import java.io.FileReader;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RequestProxy {

	private static final Logger logger = Logger.getLogger(RequestProxy.class);

	public static JSONObject YUN_DATA = new JSONObject();

	public static StringProperty usernameProperty = new SimpleStringProperty();

	public static ObjectProperty<Image> photoProperty = new SimpleObjectProperty<>();

	public static StringProperty quotaTextProperty = new SimpleStringProperty();

	public static DoubleProperty quotaProgressProperty = new SimpleDoubleProperty();

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * 获取Javascript脚本引擎
	 */
	private static JavaScriptEngine engine = ScriptUtil.getJavaScriptEngine();

	static {
		try {
			// 加载解析脚本
			engine.eval(new FileReader(RequestProxy.class.getResource("/js/util.js").getPath()));
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		}
	}

	/**
	 * 生成签名
	 *
	 * @return
	 */
	public static String sign() {
		try {
			// 执行签名脚本
			Object obj = engine.invokeFunction("sign", RequestProxy.YUN_DATA.getStr("sign3"), RequestProxy.YUN_DATA.getStr("sign1"));
			// 进行Base64加密
			return Base64.encode(obj.toString(), CharsetUtil.ISO_8859_1);
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		}
		return null;
	}

	/**
	 * 生成logid
	 *
	 * @return
	 */
	public static String logId() {
		try {
			// 执行脚本
			Object obj = engine.invokeFunction("logId", CookieUtil.getCookie("BAIDUID"));
			return obj.toString();
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		}
		return null;
	}

	private static HttpResponse httpGet(String url, JSONObject params) {
		params = params == null ? new JSONObject() : params;
		logger.info(url + "\r\n" + params.toStringPretty());

		return HttpRequest.get(url).cookie(CollectionUtil.join(CookieUtil.COOKIES, "; ")).form(params).execute();
	}

	private static HttpResponse httpPost(String url, JSONObject params) {
		params = params == null ? new JSONObject() : params;
		logger.info(url + "\r\n" + params.toStringPretty());
		return HttpRequest.post(url).cookie(CollectionUtil.join(CookieUtil.COOKIES, "; ")).form(params).execute();
	}

	private static List<BaiduFile> convertJSON2List(JSONArray array) {
		try {
			JavaType javaType = MAPPER.getTypeFactory().constructParametricType(List.class, BaiduFile.class);
			return MAPPER.readValue(array.toString(), javaType);
		} catch (IOException e) {
			logger.error(e.getMessage(), e);
			MessageDialog.show("操作失败，请稍后重试！", e);
		}
		return Collections.emptyList();
	}

	/**
	 * 直接HTTP GET访问网盘首页，获取到yunData信息
	 *
	 * @return
	 */
	public static JSONObject getYunData() {
		HttpResponse rs = httpGet(Constant.HOME_URL, null);
		Matcher matcher = Pattern.compile("var context=(.*);").matcher(rs.body());
		if (matcher.find()) {
			YUN_DATA = JSONUtil.parseObj(matcher.group(1));
			Platform.runLater(() -> {
				usernameProperty.set(YUN_DATA.getStr(Constant.NAME_KEY));
				photoProperty.set(new Image(YUN_DATA.getStr(Constant.AVATAR_KEY)));
			});
		}
		logger.info(YUN_DATA.toStringPretty());
		return YUN_DATA;
	}

	/**
	 * 获取网盘容量使用信息
	 *
	 * @return
	 */
	public static JSONObject getQuotaInfos() {
		JSONObject params = new JSONObject() {{
			put("checkexpire", 1);
			put("checkfree", 1);
			put("channel", "chunlei");
			put("web", 1);
			put("appid", 250528);
			put("clienttype", 0);
			put("bdstoken", YUN_DATA.getStr(Constant.TOKEN_KEY));
			put("logid", logId());
		}};

		HttpResponse rs = httpGet(Constant.QUOTA_URL, params);

		try {
			JSONObject result = JSONUtil.parseObj(rs.body());

			logger.info(result.toStringPretty());

			if (Constant.SUCCEED.equals(result.get("errno"))) {
				Platform.runLater(() -> {
					double used = result.getDouble("used");
					double total = result.getDouble("total");
					quotaProgressProperty.set(used / total);
					quotaTextProperty.set(Math.round(used / Constant.M_BYTE_MAX_SIZE) + "GB/" + Math.round(total / Constant.M_BYTE_MAX_SIZE) + "GB");
				});

				return result;
			} else {
				MessageDialog.show("网盘获取失败！");
				throw new RuntimeException("网盘获取失败！");
			}
		} finally {
			if (rs != null) rs.close();
		}
	}

	/**
	 * 根据路径获取文件列表
	 *
	 * @param path
	 * @return
	 * @throws Exception
	 */
	public static List<BaiduFile> getFileList(String path) {
		JSONObject params = new JSONObject() {{
			put("dir", path);
			put("order", "name");
			put("desc", 0);
			put("clienttype", 0);
			put("showempty", 0);
			put("web", 1);
			put("channel", "chunlei");
			put("appid", 250528);
			put("bdstoken", YUN_DATA.getStr(Constant.TOKEN_KEY));
			put("logid", logId());
		}};

		HttpResponse rs = httpGet(Constant.LIST_URL, params);

		try {
			JSONObject result = JSONUtil.parseObj(rs.body());

			logger.info(result.toStringPretty());

			if (Constant.SUCCEED.equals(result.get("errno")) && result.containsKey("list")) {
				return convertJSON2List(result.getJSONArray("list"));
			} else {
				MessageDialog.show("文件列表获取失败！");
				throw new RuntimeException("文件列表获取失败！");
			}
		} finally {
			if (rs != null) rs.close();
		}
	}

	/**
	 * 搜索网盘文件
	 *
	 * @param keyword
	 * @return
	 * @throws Exception
	 */
	public static List<BaiduFile> searchFileList(String keyword) {
		JSONObject params = new JSONObject() {{
			put("recursion", 1);
			put("order", "name");
			put("desc", 0);
			put("clienttype", 0);
			put("showempty", 0);
			put("web", 1);
			put("key", keyword);
			put("channel", "chunlei");
			put("appid", 250528);
			put("bdstoken", YUN_DATA.getStr(Constant.TOKEN_KEY));
			put("logid", logId());
		}};

		HttpResponse rs = httpGet(Constant.SEARCH_URL, params);

		try {
			JSONObject result = JSONUtil.parseObj(rs.body());

			logger.info(result.toStringPretty());

			if (Constant.SUCCEED.equals(result.get("errno")) && result.containsKey("list")) {
				return convertJSON2List(result.getJSONArray("list"));
			} else {
				MessageDialog.show("文件列表获取失败！");
				throw new RuntimeException("文件列表获取失败！");
			}
		} finally {
			if (rs != null) rs.close();
		}
	}

	/**
	 * 网盘文件管理
	 *
	 * @param operate  操作 重命名、删除等
	 * @param fileList
	 * @return
	 * @throws Exception
	 */
	public static boolean manager(String operate, JSONArray fileList) {
		JSONObject params = new JSONObject() {{
			put("opera", operate);
			put("async", 2);
			put("onnest", "fail");
			put("channel", "chunlei");
			put("web", 1);
			put("appid", 250528);
			put("bdstoken", YUN_DATA.getStr(Constant.TOKEN_KEY));
			put("clienttype", 0);
			put("logid", logId());
		}};

		JSONObject formData = new JSONObject() {{
			put("filelist", fileList.toString());
		}};

		HttpResponse rs = httpPost(Constant.MANAGER_URL + HttpUtil.toParams(params), formData);

		try {
			JSONObject result = JSONUtil.parseObj(rs.body());

			logger.info(result.toStringPretty());

			if (Constant.SUCCEED.equals(result.getInt("errno"))) return true;
			else {
				MessageDialog.show("操作失败，请稍后重试！");
				throw new RuntimeException("操作失败！");
			}
		} finally {
			if (rs != null) rs.close();
		}
	}

	/**
	 * 网盘文件分享
	 *
	 * @param ids       id集合
	 * @param isPrivate 是否为私密分享
	 * @param period    是否为私密分享
	 * @return
	 */
	public static JSONObject share(JSONArray ids, boolean isPrivate, int period) {
		JSONObject params = new JSONObject() {{
			put("async", 2);
			put("onnest", "fail");
			put("channel", "chunlei");
			put("web", 1);
			put("appid", 250528);
			put("bdstoken", YUN_DATA.getStr(Constant.TOKEN_KEY));
			put("clienttype", 0);
			put("logid", logId());
		}};

		JSONObject formData = new JSONObject() {{
			put("period", period);
			put("channel_list", "[]");
			put("fid_list", ids.toString());
			put("schannel", isPrivate ? 4 : 0);
			if (isPrivate) {
				put("pwd", RandomUtil.simpleUUID().substring(0, 4));
			}
		}};

		HttpResponse rs = httpPost(Constant.SHARE_URL + HttpUtil.toParams(params), formData);

		try {
			JSONObject result = JSONUtil.parseObj(rs.body());
			result.put("pwd", formData.get("pwd"));
			logger.info(result.toStringPretty());

			if (Constant.SUCCEED.equals(result.getInt("errno"))) return result;
			else {
				MessageDialog.show("文件分享失败，请稍后重试！");
				throw new RuntimeException("文件分享失败！");
			}
		} finally {
			if (rs != null) rs.close();
		}
	}

	/**
	 * 获取网盘文件下载链接
	 *
	 * @param ids id集合
	 * @return
	 */
	public static String getDownloadLink(JSONArray ids) {
		JSONObject params = new JSONObject() {{
			put("sign", sign());
			put("timestamp", YUN_DATA.get("timestamp"));
			put("fidlist", ids.toString());
			put("type", "batch");
			put("channel", "chunlei");
			put("web", 1);
			put("appid", 250528);
			put("bdstoken", YUN_DATA.getStr(Constant.TOKEN_KEY));
			put("logid", logId());
			put("clienttype", 0);
			put("startLogTime", System.currentTimeMillis());
		}};

		HttpResponse rs = httpGet(Constant.DOWNLOAD_URL, params);

		try {
			JSONObject result = JSONUtil.parseObj(rs.body());

			logger.info(result.toStringPretty());

			if (Constant.SUCCEED.equals(result.getInt("errno"))) return result.getStr("dlink");
			else {
				MessageDialog.show("下载链接获取失败，请稍后重试！");
				throw new RuntimeException("下载链接获取失败！");
			}
		} finally {
			if (rs != null) rs.close();
		}
	}
}
