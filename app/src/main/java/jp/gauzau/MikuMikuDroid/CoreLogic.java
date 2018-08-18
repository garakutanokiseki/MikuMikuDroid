package jp.gauzau.MikuMikuDroid;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.StringWriter;
import java.util.ArrayList;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.Matrix;
import android.os.Build;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

public class CoreLogic {
	// model / music data
	private ArrayList<Miku>		mMiku;
	private String				mBG;
	private MikuMotion			mCamera;
	private MediaPlayer			mMedia;
	private FakeMedia			mFakeMedia;
	private String				mMediaName;
	private long				mCurTime;
	private long				mPrevTime;
	private long				mStartTime;
	private double				mFPS;
	
	private float[]				mPMatrix = new float[16];
	private float[]				mMVMatrix = new float[16];
	private float[]				mRMatrix = new float[16];
	
	// configurations
	private String				mBase;
	private int					mMaxBone = 0;
	private Context				mCtx;
	private int					mWidth;
	private int					mHeight;
	private int					mAngle;
	private boolean				mPhysics = false;
	private boolean				mPrevPhysics = false;
	
	// temporary data
	private CameraIndex			mCameraIndex = new CameraIndex();
	private CameraPair			mCameraPair  = new CameraPair();
	
	

	private class FakeMedia {
		private WakeLock mWakeLock;
		private boolean mIsPlaying;
		private long mCallTime;
		private int mPos;
		private boolean mIsFinished;
		private int mMax;


		public FakeMedia(Context ctx) {
			PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
			mWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "MikuMikuDroid");
			mIsPlaying = false;
			mIsFinished = false;
			mPos = 0;
			mMax = 0;
		}
		
		public boolean toggleStartStop() {
			updatePos();
			if(mIsPlaying) {	// during play
				stop();
			} else {
				start();
			}
			return mIsPlaying;
		}
		
		public int getCurrentPosition() {
			updatePos();
			
			return mPos;
		}
		
		public void relaseLock() {
			if(mIsPlaying) {
				stop();
			}
			mIsFinished = false;
			mPos = 0;
			mMax = 0;
		}
		
		private void updatePos() {
			if(mIsPlaying) {
				long cur = System.currentTimeMillis();
				mPos += (cur - mCallTime);
				mCallTime = cur;

				if(mPos > mMax) {
					mPos = mMax;
					mIsFinished = true;
					stop();
				}
			}
		}
		
		private void start() {
			mWakeLock.acquire();
			mIsPlaying = true;
			mCallTime  = System.currentTimeMillis();
			if(mIsFinished) {
				mIsFinished = false;
				mPos = 0;
			}
		}
		
		private void stop() {
			mWakeLock.release();
			mIsPlaying = false;			
		}

		public void seekTo(int i) {
			mPos = i;
		}

		public void pause() {
			if(mIsPlaying) {
				stop();				
			}
		}

		public void setMax(int maxFrame) {
			mMax = maxFrame * 1000 / 30;
		}

		public int getDuration() {
			return mMax;
		}

		public boolean isPlaying() {
			return mIsPlaying;
		}
		
	}
	
	native private void btMakeWorld();
	
	native private void btDumpAll();


    static {
    	if(isArm()) {
            System.loadLibrary("bullet-jni");
            Log.d("Miku", "Use ARM native codes.");
    	}
    }
  
	public CoreLogic(Context ctx) {
		mCtx = ctx;
		mFakeMedia = new FakeMedia(ctx);

		clearMember();
		if(isArm()) {
			btMakeWorld();
		}
		
		if(new File("/sdcard/.MikuMikuDroid").exists()) {
			mBase = "/sdcard/.MikuMikuDroid/";
		} else {
			mBase = "/sdcard/MikuMikuDroid/";			
		}
	}
	
	public void setGLConfig(int max_bone) {
		if(mMaxBone == 0) {
			mMaxBone = max_bone;
			onInitialize();
		}
	}
	
	public void onInitialize() {}

	// ///////////////////////////////////////////////////////////
	// Model configurations
	public void loadModel(String modelf) throws IOException {
		// model
		PMDParser pmd = new PMDParser(mBase, modelf);
		MikuModel model = new MikuModel(mBase, pmd, mMaxBone, false);
		
		// Create Miku
		Miku miku = new Miku(model);
				
		// add Miku
		mMiku.add(miku);
	}
	
	public void loadMotion(String motionf) throws IOException {
		// motion
		VMDParser vmd = new VMDParser(motionf);
		MikuMotion motion = null;

		// check IK cache
		String vmc = motionf.replaceFirst(".vmd", "_mmcache.vmc");
		try {
			ObjectInputStream oi = new ObjectInputStream(new FileInputStream(vmc));
			motion = (MikuMotion)oi.readObject();
			motion.attachVMD(vmd);
		} catch (Exception e) {
			motion = new MikuMotion(vmd);
		}

		// Create Miku
		if(mMiku != null) {
			Miku miku = mMiku.get(mMiku.size() - 1);
			miku.attachMotion(motion);
			miku.setBonePosByVMDFramePre(0, 0, true);
			miku.setBonePosByVMDFramePost(mPhysics);
			miku.setFaceByVMDFrame(0);
			
			// store IK chache
			File f = new File(vmc);
			if(!f.exists()) {
				ObjectOutputStream oi = new ObjectOutputStream(new FileOutputStream(vmc));
				oi.writeObject(motion);
			}
		}
	}
	
	public synchronized boolean loadModelMotion(String modelf, String motionf) throws IOException, OutOfMemoryError {
		// read model/motion files
		PMDParser pmd = new PMDParser(mBase, modelf);
		
		if(pmd.isPmd()) {
			// create texture cache
			createTextureCache(pmd);
			
			// construct model/motion data structure
			MikuModel model = new MikuModel(mBase, pmd, mMaxBone, true);
			MikuMotion motion = null;
			pmd = null;
			
			// delete previous cache
			String vmc = motionf.replaceFirst(".vmd", "_mmcache.vmc");
			File vmcf = new File(vmc);
			if(vmcf.exists()) {
				vmcf.delete();
			}

			VMDParser vmd = new VMDParser(motionf);
			if(vmd.isVmd()) {
				// check IK cache
				CacheFile c = new CacheFile(mBase, "vmc");
				c.addFile(modelf);
				c.addFile(motionf);
				vmc = c.getCacheFileName();
				boolean vmc_success = true;
				try {
					ObjectInputStream oi = new ObjectInputStream(new FileInputStream(vmc));
					motion = (MikuMotion)oi.readObject();
					motion.attachVMD(vmd);
				} catch (Exception e) {
					motion = new MikuMotion(vmd);
					vmc_success = false;
				}
				vmd = null;

				// Create Miku
				Miku miku = new Miku(model);
				miku.attachMotion(motion);
				miku.setBonePosByVMDFramePre(0, 0, true);
				miku.setBonePosByVMDFramePost(mPhysics);
				miku.setFaceByVMDFrame(0);
				miku.addRenderSenario("builtin:default", "screen");
				miku.addRenderSenario("builtin:default_alpha", "screen");
				
				// store IK chache
				if(!vmc_success) {
					File f = new File(vmc);
					if(!f.exists()) {
						f.delete();
					}
					ObjectOutputStream oi = new ObjectOutputStream(new FileOutputStream(vmc));
					oi.writeObject(motion);					
				}
				
				// add Miku
				mMiku.add(miku);
				
				// set max dulation
				mFakeMedia.setMax(motion.maxFrame());
				
				return true;
			} else {
				return false;
			}
		} else {
			return false;
		}
	}
	
	public synchronized boolean loadAccessory(String modelf) throws IOException, OutOfMemoryError {
		// create cache
		CacheFile c = new CacheFile(mBase, "xc");
		c.addFile(modelf);
		String xc = c.getCacheFileName();
		ModelBuilder mb = new ModelBuilder(modelf);
		
		if(!c.hasCache() || !mb.readFromFile(xc)) {
			XParser x = new XParser(mBase, modelf, 10.0f);			
			if(x.isX()) {
				x.getModelBuilder().writeToFile(xc);
			}
			if(!mb.readFromFile(xc)) {
				return false;
			}
		}
		
		createTextureCache(mb);
		MikuModel model = new MikuModel(mBase, mb, mMaxBone, false);
		Miku miku = new Miku(model);			
		miku.addRenderSenario("builtin:nomotion", "screen");
		miku.addRenderSenario("builtin:nomotion_alpha", "screen");
		mMiku.add(miku);
		return true;
	}

	public synchronized MikuModel loadStage(String file) throws IOException, OutOfMemoryError {
		PMDParser pmd = new PMDParser(mBase, file);
		if(pmd.isPmd()) {
			createTextureCache(pmd);
			MikuModel model = new MikuModel(mBase, pmd, mMaxBone, false);
			Miku miku = new Miku(model);			
			miku.addRenderSenario("builtin:nomotion", "screen");
			miku.addRenderSenario("builtin:nomotion_alpha", "screen");
			mMiku.add(miku);
		}
		return null;
	}
	
	public synchronized String loadBG(String file) {
		String tmp = mBG;
		mBG = file;
		return tmp;
	}

	public double getFPS() {
		return mFPS;
	}

	public synchronized void loadMedia(String media) {
		if(mMedia != null) {
			mMedia.stop();
			mMedia.release();
		} else {
			mFakeMedia.relaseLock();
		}
		
		mMediaName = media;
		Uri uri = Uri.parse(media);
		mMedia = MediaPlayer.create(mCtx, uri);
		if(mMedia != null) {
			mMedia.setWakeMode(mCtx, PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE);
			mMedia.setLooping(true);
		}
	}

	public synchronized void loadCamera(String camera) throws IOException {
		mCamera = new MikuMotion(new VMDParser(camera));
	}
	
	public synchronized void togglePhysics() {
		mPhysics = !mPhysics;
		if(!mPhysics) {	// Dump Physics profile
			btDumpAll();
		}
	}

	native private void btClearAllData();

	public synchronized ArrayList<MikuModel> clear() {
		// get members for deleting textures
		ArrayList<MikuModel> models = new ArrayList<MikuModel>();
		for(Miku m: mMiku) {
			models.add(m.mModel);
		}
		
		clearMember();
		
		// clear physics world
		btClearAllData();
		
		SharedPreferences sp = mCtx.getSharedPreferences("default", 0);
		SharedPreferences.Editor ed = sp.edit();
		ed.clear();
		ed.commit();
		
		return models;
	}
	
	private void clearMember() {
		mMiku = new ArrayList<Miku>();
		mPrevTime = 0;
		mStartTime = 0;
		mCamera = null;
		if(mMedia != null) {
			mMedia.stop();
			mMedia.release();			
		} else {
			mFakeMedia.relaseLock();
		}
		mMedia = null;
		mAngle = 0;
		mBG = null;

		setDefaultCamera();
	}

	public synchronized void setScreenAngle(int angle) {
		mAngle = angle;
		if (mCamera == null) {
			setDefaultCamera();
		}
	}
	
	native private void btStepSimulation(float step, int max);
	
	public synchronized int applyCurrentMotion() {
		boolean initializePhysics = !mPrevPhysics && mPhysics;
		mPrevPhysics = mPhysics;

		calcCurrentTime();
		double frame = getCurrentFrames(32767);
		float step = (float) (getDeltaTimeMills() / 1000.0);
		if(step >= 1 || step < 0) {
			step = 0;
			initializePhysics = true;
		}
		
		if(initializePhysics) {
			btClearAllData();			
		}
		
		double prev_frame = calcPrevFrame(frame, step);
		
		int max_step = 4;
		float fixed_delta = (float) (1.0 / 60.0);
		float delta = Math.max(fixed_delta, step / max_step);

		// exec physics simulation: 60Hz
		if(isArm() && mPhysics && mMiku != null && (step != 0 || !initializePhysics)) {
			for(; step > 0.00001; step -= delta) {
				prev_frame += step > delta ? delta * 30 : step * 30;
				for (Miku miku : mMiku) {
					if(miku.hasMotion()) {
						miku.setBonePosByVMDFramePre((float) prev_frame, delta, false);
					}
				}
				if(delta == fixed_delta) {
					btStepSimulation(delta, 1);					
				} else {
					btStepSimulation(delta, 0);
				}
				for (Miku miku : mMiku) {
					if(miku.hasMotion()) {
						for(Bone b: miku.mModel.mBone) {
							b.updated = false;
						}
					}
				}
			}
		}

		if (mMiku != null) {
			for (Miku miku : mMiku) {
				if(miku.hasMotion()) {
					miku.setBonePosByVMDFramePre((float) frame, 0, initializePhysics);
					miku.setBonePosByVMDFramePost(mPhysics);
					miku.setFaceByVMDFrame((float) frame);					
				}
			}
		}
		
		setCameraByVMDFrame(frame);

		return (int) (frame * 1000 / 30);
	}
	
	private double calcPrevFrame(double frame, float step) {
		return frame - step * 30;
	}

	public void pause() {
		if (mMedia != null) {
			mMedia.pause();
		} else {
			mFakeMedia.pause();
		}
	}

	public boolean toggleStartStop() {
		if (mMedia != null) {
			if (mMedia.isPlaying()) {
				mMedia.pause();
				return false;
			} else {
				mMedia.start();
				return true;
			}
		} else {
			return mFakeMedia.toggleStartStop();
		}
	}
	
	public boolean isPlaying() {
		if(mMedia != null) {
			return mMedia.isPlaying();
		} else {
			return mFakeMedia.isPlaying();
		}
	}

	public void rewind() {
		seekTo(0);
	}
	
	public void seekTo(int pos) {
		if (mMedia != null) {
			mMedia.seekTo(pos);
		} else {
			mFakeMedia.seekTo(pos);
		}
	}
	
	public int getDulation() {
		if(mMedia != null) {
			return mMedia.getDuration();
		} else {
			return mFakeMedia.getDuration();
		}
	}
	
	public float[] getRotationMatrix() {
		return mRMatrix;
	}
	
	public void onDraw(final int pos) {}


	public void storeState() {
		SharedPreferences sp = mCtx.getSharedPreferences("default", Context.MODE_PRIVATE);
		SharedPreferences.Editor ed = sp.edit();
		
		// model & motion
		if(mMiku != null) {
			ed.putInt("ModelNum", mMiku.size());
			for(int i = 0; i < mMiku.size(); i++) {
				Miku m = mMiku.get(i);
				ed.putString(String.format("Model%d", i), m.mModel.mFileName);
				if(m.mMotion != null) {
					ed.putString(String.format("Motion%d", i), m.mMotion.mFileName);
				}
			}
		} else {
			ed.putInt("ModelNum", 0);
		}
		
		// camera
		if(mCamera != null) {
			ed.putString("Camera", mCamera.mFileName);
		}
		
		// music
		if(mMedia != null) {
			ed.putString("Music", mMediaName);
			int cur = mMedia.getCurrentPosition();
			if(cur + 100 < mMedia.getDuration()) {
				ed.putInt("Position", cur);
			}
		} else {
			int cur = mFakeMedia.getCurrentPosition();
			if(cur + 100 < mFakeMedia.getDuration()) {
				ed.putInt("Position", cur);
			}
		}
		
		ed.commit();
		Log.d("CoreLogic", "Store State");
	}
	
	public void restoreState() {
		SharedPreferences sp = mCtx.getSharedPreferences("default", Context.MODE_PRIVATE);
		
		try {
			// model & motion
			int num = sp.getInt("ModelNum", 0);
			
			String model[] = new String[num];
			String motion[] = new String[num];
			for(int i = 0; i < num; i++) {
				model[i]  = sp.getString(String.format("Model%d", i), null);
				motion[i] = sp.getString(String.format("Motion%d", i), null);
			}

			String camera = sp.getString("Camera", null);
			String music = sp.getString("Music", null);
			int pos = sp.getInt("Position", 0);
			
			// crear preferences
			Editor ed = sp.edit();
			ed.clear();
			ed.commit();
			
			// load data
			for(int i = 0; i < num; i++) {
				if(motion[i] == null) {
					if(model[i].endsWith(".x")) {
						loadAccessory(model[i]);
					} else {
						loadStage(model[i]);						
					}
				} else {
					loadModelMotion(model[i], motion[i]);
				}
			}
			
			if(camera != null) {
				loadCamera(camera);
			}			

			if(music != null) {
				loadMedia(music);
				if(mMedia != null) {
					mMedia.seekTo(pos);					
				}
			} else {
				mFakeMedia.seekTo(pos);
			}

			// restore
			storeState();
		
		} catch(IOException e) {
			Editor ed = sp.edit();
			ed.clear();
			ed.commit();
		}
	}

	public void setScreenSize(int width, int height) {
		mWidth	= width;
		mHeight	= height;
	}
	
	public int getScreenWidth() {
		return mWidth;
	}
	
	public int getScreenHeight() {
		return mHeight;
	}

	public ArrayList<Miku> getMiku() {
		return mMiku;
	}
	
	public void returnMiku(Miku miku) {
		
	}
	
	public String getBG() {
		return mBG;
	}
	
	public void returnMikuStage(Miku stage) {
		
	}
	
	public float[] getProjectionMatrix() {
		return mPMatrix;
	}
	
	public boolean checkFileIsPrepared() {
		File files = new File(mBase + "Data/toon0.bmp");
		return files.exists();
	}
	
	public File[] getModelSelector() {
		String[] ext = {
				".bmp",
				".jpg",
				".png",
				".tga"
			};
		
		String[] extm = {
				".pmd",
				".x"
			};
		
		File[] model = listFiles(mBase + "UserFile/Model/", extm);
		File[] bg    = listFiles(mBase + "UserFile/BackGround/", ext);
		File[] acc   = listFiles(mBase + "UserFile/Accessory/", ".x");
		File[] f = new File[model.length + bg.length + acc.length];
		for(int i = 0; i < model.length; i++) {
			f[i] = model[i];
		}
		for(int i = 0; i < bg.length; i++) {
			f[i + model.length] = bg[i];
		}
		for(int i = 0; i < acc.length; i++) {
			f[i + model.length + bg.length] = acc[i];
		}
		return f;
	}

	public File[] getMotionSelector() {
		return listFiles(mBase + "UserFile/Motion/", ".vmd");
	}

	public File[] getCameraSelector() {
		return listFiles(mBase + "UserFile/Motion/", ".vmd");
	}
	
	public File[] getMediaSelector() {
		String[] ext = {
			".mp3",
			".wav",
			".3gp",
			".mp4",
			".m4a",
			".ogg"
		};
		return listFiles(mBase + "UserFile/Wave/", ext);
	}

	public File[] listFiles(String dir, String ext) {
		String[] exts = new String[1];
		exts[0] = ext;
		return listFiles(dir, exts);
	}
	
	public File[] listFiles(String dir, String[] ext) {
		File file = new File(dir);
		ArrayList<File> list = listRecursive1(file, ext);
		return (File[])list.toArray(new File[0]);
	}
	
	private ArrayList<File> listRecursive1(File file, String[] ext) {
		ArrayList<File> files = new ArrayList<File>();
		if(file.exists()) {
			if(file.isFile()) {
				for(int i = 0; i < ext.length; i++) {
					if(file.getName().endsWith(ext[i])) {
						files.add(file);
						break;
					}
				}
			} else {
				File[] list = file.listFiles();
				for(int i = 0; i < list.length; i++) {
					files.addAll(listRecursive1(list[i], ext));
				}
			}
		}
		
		return files;
	}
	
	public String getRawResourceString(int id) {
		char[] buf = new char[1024];
		StringWriter sw = new StringWriter();
		
		BufferedReader is = new BufferedReader(new InputStreamReader(mCtx.getResources().openRawResource(id)));
		int n;
		try {
			while((n = is.read(buf)) != -1) {
				sw.write(buf, 0, n);
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
		
		return sw.toString();
	}
	
	public void logMemoryUsage() {
		Runtime runtime = Runtime.getRuntime();
		Log.d("CoreLogic", "totalMemory[KB] = " + (int)(runtime.totalMemory()/1024));
		Log.d("CoreLogic", "freeMemory[KB] = " + (int)(runtime.freeMemory()/1024));
		Log.d("CoreLogic", "usedMemory[KB] = " + (int)( (runtime.totalMemory() - runtime.freeMemory())/1024) );
		Log.d("CoreLogic", "maxMemory[KB] = " + (int)(runtime.maxMemory()/1024));
	}
	
	static boolean isArm() {
		return Build.CPU_ABI.contains("armeabi");
	}
	
	// ///////////////////////////////////////////////////////////
	// Some common methods

	private void createTextureCache(ModelFile pmd) {
		// create texture cache
		for(Material mat: pmd.getMaterial()) {
			if(mat.texture != null) {
				TextureFile.createCache(mBase, mat.texture, 1);
			}
			if(mat.sphere != null) {
				TextureFile.createCache(mBase, mat.sphere, 1);
			}
		}
	}
	
	private void createTextureCache(ModelBuilder pmd) {
		// create texture cache
		for(Material mat: pmd.mMaterial) {
			if(mat.texture != null) {
				TextureFile.createCache(mBase, mat.texture, 1);
			}
			if(mat.sphere != null) {
				TextureFile.createCache(mBase, mat.sphere, 1);
			}
		}
	}

	private void calcCurrentTime() {
		// update previous time
		mPrevTime = mCurTime;
		
		// calculate current time
		if (mMedia != null) {
			mCurTime = mMedia.getCurrentPosition();
			if(mMedia.isPlaying()) {
				long timeLocal = System.currentTimeMillis();
				if (Math.abs(timeLocal - mStartTime - mCurTime) > 500 || mMedia.isPlaying() == false) {
					mStartTime = timeLocal - mCurTime;
				} else {
					mCurTime = timeLocal - mStartTime;
				}				
			}
		} else {
			mCurTime = mFakeMedia.getCurrentPosition();
		}
	}
	
	protected double getCurrentFrames(int max_frame) {
		double frame;
		frame = ((float) mCurTime * 30.0 / 1000.0);
		if (frame > max_frame) {
			frame = max_frame;
		}
		mFPS = 1000.0 / (mCurTime - mPrevTime);

		return frame;
	}
	
	private long getDeltaTimeMills() {
		return mCurTime - mPrevTime;
	}

	protected void setCameraByVMDFrame(double frame) {
		if (mCamera != null) {
			CameraPair cp = mCamera.findCamera((float) frame, mCameraPair);
			CameraIndex c = mCamera.interpolateLinear(cp, (float) frame, mCameraIndex);
			if (c != null) {
				setCamera(c.length, c.location, c.rotation, c.view_angle, mWidth, mHeight);
			}
		} else {
			if (mAngle == 0) {
				mCameraIndex.location[0] = 0;
				mCameraIndex.location[1] = 10; // 13
				mCameraIndex.location[2] = 0;
				mCameraIndex.rotation[0] = 0;
				mCameraIndex.rotation[1] = 0;
				mCameraIndex.rotation[2] = 0;
				setCamera(-35f, mCameraIndex.location, mCameraIndex.rotation, 45, mWidth, mHeight); // -38f
			} else {
				mCameraIndex.location[0] = 0;
				mCameraIndex.location[1] = 10;
				mCameraIndex.location[2] = 0;
				mCameraIndex.rotation[0] = 0;
				mCameraIndex.rotation[1] = 0;
				mCameraIndex.rotation[2] = 0;
				setCamera(-30f, mCameraIndex.location, mCameraIndex.rotation, 45, mWidth, mHeight);
			}
		}
	}

	protected void setCamera(float d, float[] pos, float[] rot, float angle, int width, int height) {
		// Projection Matrix
		float s = (float) Math.sin(angle * Math.PI / 360);
		Matrix.setIdentityM(mPMatrix, 0);
		if (mAngle == 90) {
			Matrix.frustumM(mPMatrix, 0, -s, s, -s * height / width, s * height / width, 1f, 3500f);
		} else {
			Matrix.frustumM(mPMatrix, 0, -s * width / height, s * width / height, -s, s, 1f, 3500f);
		}
		Matrix.scaleM(mPMatrix, 0, 1, 1, -1); // to right-handed
		Matrix.rotateM(mPMatrix, 0, mAngle, 0, 0, -1); // rotation

		Matrix.multiplyMM(mPMatrix, 0, mPMatrix, 0, mRMatrix, 0);	// device rotation

		// camera
		Matrix.translateM(mPMatrix, 0, 0, 0, -d);
		Matrix.rotateM(mPMatrix, 0, rot[2], 0, 0, 1f);
		Matrix.rotateM(mPMatrix, 0, rot[0], 1f, 0, 0);
		Matrix.rotateM(mPMatrix, 0, rot[1], 0, 1f, 0);
		Matrix.translateM(mPMatrix, 0, -pos[0], -pos[1], -pos[2]);

		// model-view matrix (is null)
		Matrix.setIdentityM(mMVMatrix, 0);
	}

	protected void setDefaultCamera() {
		Matrix.setIdentityM(mRMatrix, 0);
		if (mAngle == 0) {
			mCameraIndex.location[0] = 0;
			mCameraIndex.location[1] = 10; // 13
			mCameraIndex.location[2] = 0;
			mCameraIndex.rotation[0] = 0;
			mCameraIndex.rotation[1] = 0;
			mCameraIndex.rotation[2] = 0;
			setCamera(-35f, mCameraIndex.location, mCameraIndex.rotation, 45, mWidth, mHeight); // -38f
		} else {
			mCameraIndex.location[0] = 0;
			mCameraIndex.location[1] = 10;
			mCameraIndex.location[2] = 0;
			mCameraIndex.rotation[0] = 0;
			mCameraIndex.rotation[1] = 0;
			mCameraIndex.rotation[2] = 0;
			setCamera(-30f, mCameraIndex.location, mCameraIndex.rotation, 45, mWidth, mHeight);
		}
	}

}
