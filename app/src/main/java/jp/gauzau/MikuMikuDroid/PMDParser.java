package jp.gauzau.MikuMikuDroid;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;

import android.util.Log;

public class PMDParser extends ParserBase implements ModelFile {
	private String  mFileName;
	private boolean mIsPmd;
	private String mModelName;
	private String mDescription;
	private ArrayList<Material> mMaterial;
	private ArrayList<Bone> mBone;
	private ArrayList<IK> mIK;
	private ArrayList<Face> mFace;
	private ArrayList<Short> mSkinDisp;
	private ArrayList<String> mBoneDispName;
	private ArrayList<BoneDisp> mBoneDisp;
	private byte mHasEnglishName;
	private String mEnglishModelName;
	private String mEnglishComment;
	private ArrayList<String> mEnglishBoneName;
	private ArrayList<String> mEnglishSkinName;
	private ArrayList<String> mToonFileName;
	private ArrayList<String> mEnglishBoneDispName;
	private ArrayList<RigidBody> mRigidBody;
	private ArrayList<Joint> mJoint;
	private boolean mIsOneSkinning = true;
	
	public ShortBuffer	mIndexBuffer;
	public FloatBuffer	mVertBuffer;
	public ShortBuffer	mWeightBuffer;
	
	private int mVertexPos;
	private int[] mInvMap;

	public PMDParser(String base, String file) throws IOException {
		super(file);
		mFileName = file;
		mIsPmd = false;
		File f = new File(file);
		String path = f.getParent() + "/";

		try {
			parsePMDHeader();
			if(mIsPmd) {
				parsePMDVertexList();
				parsePMDIndexList();
				parsePMDMaterialList(path);
				parsePMDBoneList();
				parsePMDIKList();
				parsePMDFaceList();
				parsePMDSkinDisp();
				parsePMDBoneDispName();
				parsePMDBoneDisp();
				if (!isEof()) {
					parsePMDEnglish();
					parsePMDToonFileName(path, base);
					parsePMDRigidBody();
					parsePMDJoint();
				} else {
					mToonFileName = new ArrayList<String>(11);
					mToonFileName.add(0, base + "Data/toon0.bmp");
					for (int i = 0; i < 10; i++) {
						String str = String.format(base + "Data/toon%02d.bmp", i + 1);
						mToonFileName.add(i + 1, str);
					}

				}				
			}
		} catch (IOException e) {
			e.printStackTrace();
			mIsPmd = false;
		}
	}
	
	private void parsePMDJoint() {
		int num = getInt();
		Log.d("PMDParser", "Joint: " + String.valueOf(num));
		if(num > 0) {
			mJoint = new ArrayList<Joint>(num);
			for(int i = 0; i < num; i++) {
				Joint j = new Joint();
				j.name				= getString(20);
				j.rigidbody_a		= getInt();
				j.rigidbody_b		= getInt();
				j.position			= new float[3];
				j.rotation			= new float[3];
				j.const_position_1	= new float[3];
				j.const_position_2	= new float[3];
				j.const_rotation_1	= new float[3];
				j.const_rotation_2	= new float[3];
				j.spring_position	= new float[3];
				j.spring_rotation	= new float[3];
				
				getFloat(j.position);
				getFloat(j.rotation);
				getFloat(j.const_position_1);
				getFloat(j.const_position_2);
				getFloat(j.const_rotation_1);
				getFloat(j.const_rotation_2);
				getFloat(j.spring_position);
				getFloat(j.spring_rotation);
				
				mJoint.add(j);
			}
		}
	}

	private void parsePMDRigidBody() {
		int num = getInt();
		Log.d("PMDParser", "RigidBody: " + String.valueOf(num));
		if(num > 0) {
			mRigidBody = new ArrayList<RigidBody>(num);
			for(int i = 0; i < num; i++) {
				RigidBody rb = new RigidBody();
				
				rb.name			= getString(20);
				rb.bone_index	= getShort();
				rb.group_index	= getByte();
				rb.group_target = getShort();
				rb.shape		= getByte();
				rb.size			= new float[3];		// w, h, d
				rb.location		= new float[3];		// x, y, z
				rb.rotation		= new float[3];
				getFloat(rb.size);
				getFloat(rb.location);
				getFloat(rb.rotation);
				rb.weight		= getFloat();
				rb.v_dim		= getFloat();
				rb.r_dim		= getFloat();
				rb.recoil		= getFloat();
				rb.friction		= getFloat();
				rb.type			= getByte();
				
				rb.btrb			= -1;	// physics is not initialized yet
				
				mRigidBody.add(rb);
			}			
		}
	}

	private void parsePMDEnglish() throws IOException {
		mHasEnglishName = getByte();
		Log.d("PMDParser", "HasEnglishName: " + String.valueOf(mHasEnglishName));
		if (mHasEnglishName == 1) {
			parsePMDEnglishName();
			parsePMDEnglishBoneList();
			parsePMDEnglishSkinList();
			parsePMDEnglishBoneDispName();
		}

	}

	private void parsePMDEnglishBoneDispName() throws IOException {
		int num = mBoneDispName.size();
		if(num > 0) {
			mEnglishBoneDispName = new ArrayList<String>(num);
			for (int i = 0; i < num; i++) {
				String str = getString(50);
				mEnglishBoneDispName.add(i, str);
			}			
		}
	}

	private void parsePMDToonFileName(String path, String base) throws IOException {
		mToonFileName = new ArrayList<String>(11);
		mToonFileName.add(0, base + "Data/toon0.bmp");
		for (int i = 0; i < 10; i++) {
			String str = getString(100);
			str = str.replace('\\', '/');
			if (isExist(path + str)) {
				mToonFileName.add(i + 1, path + str);
			} else {
				String toon = base + "Data/" + str;
				if(!isExist(toon)) {
					mToonFileName.add(i + 1, String.format(base + "Data/toon%02d.bmp", i + 1));
					Log.d("PMDParser", String.format("Toon texture not found: %s, fall thru to default texture.", str));
				}
				mToonFileName.add(i + 1, base + "Data/" + str);
			}
		}
	}

	private void parsePMDEnglishSkinList() throws IOException {
		int num = mSkinDisp.size();
		Log.d("PMDParser", "EnglishSkinName: " + String.valueOf(num));
		if(num > 0) {
			mEnglishSkinName = new ArrayList<String>(num);
			for (int i = 0; i < num; i++) {
				String str = getString(20);
				mEnglishSkinName.add(i, str);
			}			
		}
	}

	private void parsePMDEnglishBoneList() throws IOException {
		int num = mBone.size();
		Log.d("PMDParser", "EnglishBoneName: " + String.valueOf(num));
		if(num > 0) {
			mEnglishBoneName = new ArrayList<String>(num);
			for (int i = 0; i < num; i++) {
				String str = getString(20);
				mEnglishBoneName.add(i, str);
			}			
		}
	}

	private void parsePMDEnglishName() throws IOException {
		mEnglishModelName = getString(20);
		mEnglishComment = getString(256);
		Log.d("PMDParser", "EnglishModelName: " + mEnglishModelName);
		Log.d("PMDParser", "EnglishComment: " + mEnglishComment);
	}

	private void parsePMDBoneDisp() {
		int mBoneDispNum = getInt();
		Log.d("PMDParser", "BoneDisp: " + String.valueOf(mBoneDispNum));
		if (mBoneDispNum > 0) {
			mBoneDisp = new ArrayList<BoneDisp>(mBoneDispNum);
			if (mBoneDisp == null) {
				mIsPmd = false;
				return;
			}
			for (int i = 0; i < mBoneDispNum; i++) {
				BoneDisp bd = new BoneDisp();

				bd.bone_index = getShort();
				bd.bone_disp_frame_index = getByte();

				mBoneDisp.add(i, bd);
			}
		} else {
			mBoneDisp = null;
		}
	}

	private void parsePMDBoneDispName() {
		byte num = getByte();
		Log.d("PMDParser", "BoneDispName: " + String.valueOf(num));
		if (num > 0) {
			mBoneDispName = new ArrayList<String>(num);
			if (mBoneDispName == null) {
				mIsPmd = false;
				return;
			}
			for (int i = 0; i < num; i++) {
				String str = getString(50);
				mBoneDispName.add(i, str);
			}
		} else {
			mBoneDispName = null;
		}
	}

	private void parsePMDSkinDisp() {
		byte num = getByte();
		Log.d("PMDParser", "SkinDisp: " + String.valueOf(num));
		if (num > 0) {
			mSkinDisp = new ArrayList<Short>(num);
			if (mSkinDisp == null) {
				mIsPmd = false;
				return;
			}
			for (int i = 0; i < num; i++) {
				short idx = getShort();
				mSkinDisp.add(i, idx);
			}
		} else {
			mSkinDisp = null;
		}
	}

	private void parsePMDFaceList() {
		short num = getShort();
		int acc = 0;
		boolean isArm = CoreLogic.isArm();
		Log.d("PMDParser", "Face: " + String.valueOf(num));
		if (num > 0) {
			mFace = new ArrayList<Face>(num);
			float[] buf = new float[3];
			for (int i = 0; i < num; i++) {
				Face face = new Face();

				face.name = getString(20);
				face.face_vert_count = getInt();
				face.face_type = getByte();

//				face.face_vert_data = new ArrayList<FaceVertData>(face.face_vert_count);
				acc += face.face_vert_count;
				if(isArm) {	// for ARM native code
					face.face_vert_index_native  = ByteBuffer.allocateDirect(face.face_vert_count * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
					face.face_vert_offset_native = ByteBuffer.allocateDirect(face.face_vert_count * 4 * 3).order(ByteOrder.nativeOrder()).asFloatBuffer();
					for (int j = 0; j < face.face_vert_count; j++) {
						face.face_vert_index_native.put(face.face_type == 0 ? mInvMap[getInt()] : getInt());
						getFloat(buf);
						face.face_vert_offset_native.put(buf);
					}
					face.face_vert_index_native.position(0);
					face.face_vert_offset_native.position(0);
				} else {	// for universal code
					face.face_vert_base    = new float[face.face_vert_count*3];
					face.face_vert_cleared = new boolean[face.face_vert_count];
					face.face_vert_updated = new boolean[face.face_vert_count];
					face.face_vert_index   = new int[face.face_vert_count];
					face.face_vert_offset  = new float[face.face_vert_count*3];
					
					for (int j = 0; j < face.face_vert_count; j++) {
						face.face_vert_index[j] = face.face_type == 0 ? mInvMap[getInt()] : getInt();
						face.face_vert_offset[j * 3 + 0] = getFloat();
						face.face_vert_offset[j * 3 + 1] = getFloat();
						face.face_vert_offset[j * 3 + 2] = getFloat();
						face.face_vert_cleared[j] = true;
					}
				}
				
				mFace.add(i, face);
			}
			Log.d("PMDParser", String.format("Total Face Vert: %d", acc));
		} else {
			mFace = null;
		}
		mInvMap = null;
	}

	private void parsePMDIKList() {
		// the number of Vertexes
		short num = getShort();
		Log.d("PMDParser", "IK: " + String.valueOf(num));
		if (num > 0) {
			mIK = new ArrayList<IK>(num);
			for (int i = 0; i < num; i++) {
				IK ik = new IK();

				ik.ik_bone_index = getShort();
				ik.ik_target_bone_index = getShort();
				ik.ik_chain_length = getByte();
				ik.iterations = getShort();
				ik.control_weight = getFloat();

				ik.ik_child_bone_index = new Short[ik.ik_chain_length];
				for (int j = 0; j < ik.ik_chain_length; j++) {
					ik.ik_child_bone_index[j] = getShort();
				}

				mIK.add(i, ik);
			}
		} else {
			mIK = null;
		}
	}

	private void parsePMDBoneList() {
		// the number of Vertexes
		short num = getShort();
		Log.d("PMDParser", "BONE: " + String.valueOf(num));
		if (num > 0) {
			mBone = new ArrayList<Bone>(num);
			for (int i = 0; i < num; i++) {
				Bone bone = new Bone();

				bone.name_bytes = getStringBytes(new byte[20], 20);
				bone.name = toString(bone.name_bytes);
				bone.parent = getShort();
				bone.tail = getShort();
				bone.type = getByte();
				bone.ik = getShort();

				bone.head_pos = new float[4];
				bone.head_pos[0] = getFloat();
				bone.head_pos[1] = getFloat();
				bone.head_pos[2] = getFloat();
				bone.head_pos[3] = 1; // for IK (Miku:getCurrentPosition:Matrix.multiplyMV(v, 0, d, 0, b.head_pos, 0)

				bone.motion = null;
				bone.quaternion = new double[4]; // for skin-mesh preCalkIK
				bone.matrix = new float[16]; // for skin-mesh animation
				bone.matrix_current = new float[16]; // for temporary (current bone matrix that is not include parent rotation
				bone.updated = false; // whether matrix is updated by VMD or not
				bone.is_leg = bone.name.contains("�Ђ�");
				
				if (bone.tail != -1) {
					mBone.add(i, bone);
				}
			}
		} else {
			mBone = null;
		}
	}

	private void parsePMDMaterialList(String path) {
		// the number of Vertexes
		int num = getInt();
		Log.d("PMDParser", "MATERIAL: " + String.valueOf(num));
		if (num > 0) {
			mMaterial = new ArrayList<Material>(num);
			int acc = 0;
			for (int i = 0; i < num; i++) {
				Material material = new Material();

				material.diffuse_color = new float[4];
				material.specular_color = new float[3];
				material.emmisive_color = new float[3];
				
				getFloat(material.diffuse_color);
				material.power = getFloat();
				getFloat(material.specular_color);
				getFloat(material.emmisive_color);

				material.toon_index = getByte();
				material.toon_index += 1; // 0xFF to toon0.bmp, 0x00 to toon01.bmp, 0x01 to toon02.bmp...
				material.edge_flag = getByte();
				material.face_vert_count = getInt();
				material.texture = getString(20);
				if (material.texture.length() == 0) {
					material.texture = null;
					material.sphere = null;
				} else {
					material.texture = material.texture.replace('\\', '/');
					String sp[] = material.texture.split("\\*");
					if (sp.length == 2) {
						material.texture = path + sp[0];
						material.sphere = path + sp[1];
					} else {
						if(material.texture.endsWith("spa") || material.texture.endsWith("sph")) {
							material.sphere = path + material.texture;
							material.texture = null;
						} else {
							material.texture = path + material.texture;
							material.sphere = null;							
						}
					}
				}
				if(material.texture != null) {
					if(!new File(material.texture).exists()) {
						mIsPmd = false;
						Log.d("PMDParser", String.format("Texture not found: %s", material.texture));
					}
				}
				if(material.sphere != null) {
					if(!new File(material.sphere).exists()) {
						material.sphere = null;	// fake
//						mIsPmd = false;
//						Log.d("PMDParser", String.format("Sphere map texture not found: %s", material.sphere));
					}
				}
				
				material.face_vert_offset = acc;

				acc = acc + material.face_vert_count;
				mMaterial.add(i, material);
			}
			Log.d("PMDParser", "CHECKSUM IN MATERIAL: " + String.valueOf(acc));
		} else {
			mMaterial = null;
		}

	}

	private void parsePMDIndexList() {
		// the number of Vertexes
		int num = getInt();
		Log.d("PMDParser", "INDEX: " + String.valueOf(num));
		if (num > 0) {
			mInvMap = new int[mVertBuffer.capacity() / 8];
			for(int i = 0; i < mInvMap.length; i++) {
				mInvMap[i] = -1;
			}
			mIndexBuffer = ByteBuffer.allocateDirect(num * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
			float[] data = new float[8]; 
			int acc = 0;
			for (int i = 0; i < num; i++) {
				int vi = (0x0000ffff & getShort());
				if(mInvMap[vi] < 0) {
					int pos = position();
					position(mVertexPos + vi * 38);
					getFloat(data);
					short bone_num_0 = getShort();
					short bone_num_1 = getShort();
					byte bone_weight = getByte();
//					byte edge_flag = getByte();
					position(pos);
					
					if (bone_weight < 50) { // swap to make bone_num_0 as main bone
						short tmp = bone_num_0;
						bone_num_0 = bone_num_1;
						bone_num_1 = tmp;
						bone_weight = (byte) (100 - bone_weight);
					}
					
					if(bone_weight != 100) {
						mIsOneSkinning = false;
					}
					
					mVertBuffer.put(data);
					mWeightBuffer.put(bone_num_0);
					mWeightBuffer.put(bone_num_1);
					mWeightBuffer.put(bone_weight);

					mInvMap[vi] = acc++;
				}
				mIndexBuffer.put((short) mInvMap[vi]);
			}
			mIndexBuffer.position(0);
			mVertBuffer.position(0);
			mWeightBuffer.position(0);
		} else {
			mIndexBuffer = null;
			mVertBuffer = null;
			mWeightBuffer = null;
		}
	}

	private void parsePMDVertexList() {
		// the number of Vertexes
		int num = getInt();
		Log.d("PMDParser", "VERTEX: " + String.valueOf(num));
		if (num > 0) {
			mVertexPos = position();
			mVertBuffer = ByteBuffer.allocateDirect(num * 4 * 8).order(ByteOrder.nativeOrder()).asFloatBuffer();
			mWeightBuffer = ByteBuffer.allocate(num * 2 * 3).asShortBuffer();
			position(mVertexPos + num * 38);
		} else {
			mVertBuffer = null;
		}
	}

	private void parsePMDHeader() {
		// Magic
		String s = getString(3);
		Log.d("PMDParser", "MAGIC: " + s);
		if (s.equals("Pmd")) {
			mIsPmd = true;
		}

		// Version
		float f = getFloat();
		Log.d("PMDParser", "VERSION: " + String.valueOf(f));

		// Model Name
		mModelName = getString(20);
		Log.d("PMDParser", "MODEL NAME: " + mModelName);

		// description
		mDescription = getString(256);
		Log.d("PMDParser", "DESCRIPTION: " + mDescription);
	}

	public boolean isPmd() {
		return mIsPmd;
	}
	
	public FloatBuffer getVertexBuffer() {
		return mVertBuffer;
	}
	
	public IntBuffer getIndexBufferI() {
		return null;
	}

	public ShortBuffer getIndexBufferS() {
		return mIndexBuffer;
	}

	public ShortBuffer getWeightBuffer() {
		return mWeightBuffer;
	}

	public ArrayList<Vertex> getVertex() {
		return null;
	}

	public ArrayList<Integer> getIndex() {
		return null;
	}

	public ArrayList<Material> getMaterial() {
		return mMaterial;
	}

	public ArrayList<Bone> getBone() {
		return mBone;
	}

	public ArrayList<String> getToonFileName() {
		return mToonFileName;
	}

	public ArrayList<IK> getIK() {
		return mIK;
	}

	public ArrayList<Face> getFace() {
		return mFace;
	}
	
	public ArrayList<RigidBody> getRigidBody() {
		return mRigidBody;
	}
	
	public ArrayList<Joint> getJoint() {
		return mJoint;
	}
	
	public String getFileName() {
		return mFileName;
	}
	
	public boolean isOneSkinning() {
		return mIsOneSkinning;
	}

	public void recycle() {
		mModelName = null;
		mDescription = null;
		mVertBuffer = null;
		mIndexBuffer = null;
		mWeightBuffer = null;

		mSkinDisp = null;
		mEnglishModelName = null;
		mEnglishComment = null;
		mEnglishBoneName = null;
		mEnglishSkinName = null;
		mEnglishBoneDispName = null;
		close();
	}

	public void recycleVertex() {
		mVertBuffer = null;
	}
}
