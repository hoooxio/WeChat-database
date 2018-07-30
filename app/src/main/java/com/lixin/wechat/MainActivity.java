package com.lixin.wechat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.widget.Toast;

import net.sqlcipher.Cursor;
import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SQLiteDatabaseHook;

import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity
        implements ActivityCompat.OnRequestPermissionsResultCallback {
    public static final String WX_ROOT_PATH = "/data/data/com.tencent.mm/";
    public static final int REQUEST_READ_PHONE_STAT = 1;

    // U ID 文件路径
    private static final String WX_SP_UIN_PATH = WX_ROOT_PATH + "shared_prefs/auth_info_key_prefs.xml";


    private String mDbPassword;


    private static final String WX_DB_DIR_PATH = WX_ROOT_PATH + "MicroMsg";
    private List<File> mWxDbPathList = new ArrayList<>();
    private static final String WX_DB_FILE_NAME = "EnMicroMsg.db";


    private String mCurrApkPath = "/data/data/" + MyApplication.getContextObject().getPackageName() + "/";
    private static final String COPY_WX_DATA_DB = "wx_data.db";


    // 提交参数
    private int count = 0;

    private String IMEI;

    private String Uin;
    ;

    private Thread type;

    // private EditText link;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 获取root权限
        execRootCmd("chmod -R 777 " + WX_ROOT_PATH);

        // 获取微信的U id
        initCurrWxUin();

        // 获取 IMEI 唯一识别码
        TelephonyManager phone = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        // 当没有权限时，向用户申请权限。忘了x.0之后的系统需要申请权限才可以
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_PHONE_STATE}, REQUEST_READ_PHONE_STAT);
        } else {
            IMEI = phone.getDeviceId();
        }

        System.out.println("IMEI" + IMEI);

        // 根据imei和uin生成的md5码，获取数据库的密码（去前七位的小写字母）
        initDbPassword(IMEI, Uin);

        System.out.println(mDbPassword + "数据库的密码");

        System.out.println("开始统计好友数量");

        //  递归查询微信本地数据库文件
        StringBuilder sb = new StringBuilder(100);
        // 当前 uin 所在的数据库位置为 /data/data/com.tencent.mm/MicroMsg/MD5("mm" + uin)/EnMicroMsg.db
        sb.append(WX_DB_DIR_PATH).append("/").append(getMD5("mm" + Uin)).append("/").append(WX_DB_FILE_NAME);
        System.out.println("当前 uin 数据库位置：" + sb.toString());
        File file = new File(sb.toString());
        if (!file.exists()) {
            LogUtil.e("该 uin 对应的数据库 " + sb.toString() + "不存在");
            return;
        }

        // 将微信数据库拷贝一个新的出来，如果直接操作微信数据库会导致微信崩溃
        String copyFilePath = mCurrApkPath + COPY_WX_DATA_DB;
        copyFile(sb.toString(), copyFilePath);
        File copyFile = new File(copyFilePath);
        openWxDb(copyFile);
    }

    @Override
    @SuppressWarnings("all")
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case REQUEST_READ_PHONE_STAT:
                // 授权成功时回调：设置 IMEI
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    TelephonyManager tm = (TelephonyManager) this.getSystemService(TELEPHONY_SERVICE);
                    IMEI = tm.getDeviceId();
                }
                break;
            default:
                break;
        }
    }

    /**
     * 执行linux指令
     *
     * @param paramString
     */
    public void execRootCmd(String paramString) {
        try {
            Process localProcess = Runtime.getRuntime().exec("su");
            Object localObject = localProcess.getOutputStream();
            DataOutputStream localDataOutputStream = new DataOutputStream((OutputStream) localObject);
            String str = String.valueOf(paramString);
            localObject = str + "\n";
            localDataOutputStream.writeBytes((String) localObject);
            localDataOutputStream.flush();
            localDataOutputStream.writeBytes("exit\n");
            localDataOutputStream.flush();
            localProcess.waitFor();
            localObject = localProcess.exitValue();
        } catch (Exception localException) {
            localException.printStackTrace();
        }
    }


    /**
     * 获取微信的uid
     * 微信的uid存储在SharedPreferences里面
     * 存储位置\data\data\com.tencent.mm\shared_prefs\auth_info_key_prefs.xml
     */
    private void initCurrWxUin() {
        Uin = null;
        File file = new File(WX_SP_UIN_PATH);
        try {
            FileInputStream in = new FileInputStream(file);
            SAXReader saxReader = new SAXReader();
            Document document = saxReader.read(in);
            Element root = document.getRootElement();
            List<Element> elements = root.elements();
            for (Element element : elements) {
                if ("_auth_uin".equals(element.attributeValue("name"))) {
                    Uin = element.attributeValue("value");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            LogUtil.e("获取微信uid失败，请检查auth_info_key_prefs文件权限");
        }
    }

    /**
     * 根据imei和uin生成的md5码，获取数据库的密码（去前七位的小写字母）
     *
     * @param imei
     * @param uin
     * @return
     */
    private void initDbPassword(String imei, String uin) {
        if (TextUtils.isEmpty(imei) || TextUtils.isEmpty(uin)) {
            LogUtil.e("初始化数据库密码失败：imei或uid为空");
            return;
        }
        String md5 = getMD5(imei + uin);
        System.out.println(imei + uin + "初始数值");
        System.out.println(md5 + "MD5");
        String password = md5.substring(0, 7).toLowerCase();
        System.out.println("加密后" + password);
        mDbPassword = password;
    }

    public String getMD5(String info) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(info.getBytes("UTF-8"));
            byte[] encryption = md5.digest();

            StringBuffer strBuf = new StringBuffer();
            for (int i = 0; i < encryption.length; i++) {
                if (Integer.toHexString(0xff & encryption[i]).length() == 1) {
                    strBuf.append("0").append(Integer.toHexString(0xff & encryption[i]));
                } else {
                    strBuf.append(Integer.toHexString(0xff & encryption[i]));
                }
            }

            return strBuf.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    /**
     * 复制单个文件
     *
     * @param oldPath String 原文件路径 如：c:/fqf.txt
     * @param newPath String 复制后路径 如：f:/fqf.txt
     * @return boolean
     */
    public void copyFile(String oldPath, String newPath) {
        try {
            int byteRead = 0;
            File oldFile = new File(oldPath);
            if (oldFile.exists()) { //文件存在时
                InputStream inStream = new FileInputStream(oldPath); //读入原文件
                FileOutputStream fs = new FileOutputStream(newPath);
                byte[] buffer = new byte[1444];
                while ((byteRead = inStream.read(buffer)) != -1) {
                    fs.write(buffer, 0, byteRead);
                }
                inStream.close();
            }
        } catch (Exception e) {
            System.out.println("复制单个文件操作出错");
            e.printStackTrace();
        }
    }

    /**
     * 连接数据库
     *
     * @param dbFile
     */
    private void openWxDb(File dbFile) {
        Context context = MyApplication.getContextObject();
        SQLiteDatabase.loadLibs(context);
        SQLiteDatabaseHook hook = new SQLiteDatabaseHook() {
            public void preKey(SQLiteDatabase database) {
            }

            public void postKey(SQLiteDatabase database) {
                database.rawExecSQL("PRAGMA cipher_migrate;"); //兼容2.0的数据库
            }
        };

        try {
            doQuery(dbFile, hook);

        } catch (Exception e) {
            LogUtil.e("读取数据库信息失败 尝试MEID破解");
//            e.printStackTrace();
            //打开数据库连接
            // 请自行添加自己手机的MEID  MEID 无法直接获取
            initDbPassword("A0000069594FA0", Uin);

            System.out.println(mDbPassword + "MEID---密码");
            count = 0;
            doQuery(dbFile, hook);
        }
    }

    private void doQuery(File dbFile, SQLiteDatabaseHook hook) {
        //打开数据库连接
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dbFile, mDbPassword, null, hook);

        //查询所有联系人（verifyFlag!=0:公众号等类型，群里面非好友的类型为4，未知类型2）
        // Cursor c1 = db.rawQuery("select * from rcontact where verifyFlag = 0  and type != 4 and type != 2 and type !=33 limit 20, 9999", null);
        Cursor c1 = db.rawQuery("select * from rcontact where username not like 'gh_%' and verifyFlag<>24 and verifyFlag<>29 and verifyFlag<>56 and type<>33 and type<>70 and verifyFlag=0 and type<>4 and type<>0 and showHead<>43 and type<>65536", null);

        while (c1.moveToNext()) {
            String type = c1.getString(c1.getColumnIndex("type"));
            System.out.println(type + "参数");
            count++;
        }

        System.out.println("总共参数" + count);
        Toast.makeText(getApplicationContext(), "好友总数" + count, Toast.LENGTH_SHORT).show();
        c1.close();
        db.close();
    }
}
