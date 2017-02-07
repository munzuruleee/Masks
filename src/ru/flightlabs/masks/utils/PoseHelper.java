package ru.flightlabs.masks.utils;

import android.content.Context;
import android.opengl.Matrix;
import android.util.Log;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.MatOfRect;
import org.opencv.core.Point;
import org.opencv.core.Point3;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.util.ArrayList;

import ru.flightlabs.masks.CompModel;
import ru.flightlabs.masks.DetectionBasedTracker;
import ru.flightlabs.masks.R;
import ru.flightlabs.masks.activity.FdActivity2;
import ru.flightlabs.masks.activity.Settings;
import ru.flightlabs.masks.camera.Effect;
import ru.flightlabs.masks.renderer.Model;

/**
 * Created by sov on 06.01.2017.
 */

public class PoseHelper {

    CompModel compModel;

    private static final String TAG = "PoseHelper";
    int[] p3d1;
    int[] p2d1;
    Mat intrinsics;
    Mat rvec;
    Mat tvec;

    MatOfPoint3f objectPoints;
    double[] pointsListArray;
    MatOfPoint2f imagePoints;
    double[] pointsList2Array;

    Mat cvToGl;
    Mat rotation;
    Mat viewMatrix;
    Mat initialParams;
    String modelPath;


    public PoseHelper(CompModel compModel) {
        this.compModel = compModel;
    }
    public void init(Context context, int width, int height) {
        intrinsics = Mat.eye(3, 3, CvType.CV_64F);
        intrinsics.put(0, 0, width); // ?
        intrinsics.put(1, 1, width); // ?
        intrinsics.put(0, 2, width / 2);
        intrinsics.put(1, 2, height / 2);
        intrinsics.put(2, 2, 1);

        rvec = new Mat(3, 1, CvType.CV_64F);
        tvec = new Mat(3, 1, CvType.CV_64F);
        // TODO calculate values somehow
        tvec.put(0, 0, 0);
        tvec.put(1, 0, 0);
        tvec.put(2, 0, 2);
        rvec.put(0, 0, -0.235);
        rvec.put(1, 0, 0);
        rvec.put(2, 0, 0);

        String[] p3d = context.getResources().getStringArray(R.array.points2DTo3D);
        p2d1 = new int[p3d.length];
        p3d1 = new int[p3d.length];
        for (int i = 0; i < p3d.length; i++) {
            String p = p3d[i];
            String[] w2 = p.split(";");
            p2d1[i] = Integer.parseInt(w2[0]);
            p3d1[i] = Integer.parseInt(w2[1]);
        }
        objectPoints = new MatOfPoint3f(new Mat(p3d1.length, 1, CvType.CV_32FC3));
        pointsListArray = new double[p3d1.length * 3];
        imagePoints  = new MatOfPoint2f(new Mat(p3d1.length, 1, CvType.CV_32FC2));
        pointsList2Array = new double[p3d1.length * 2];

        cvToGl = new Mat(4, 4, CvType.CV_64F, new Scalar(0));
        cvToGl.put(0, 0, 1.0f);
        cvToGl.put(1, 1, -1.0f);
        cvToGl.put(2, 2, -1.0f);
        cvToGl.put(3, 3, 1.0f);

        rotation = new Mat(4, 4, CvType.CV_64F);
        viewMatrix = new Mat(4, 4, CvType.CV_64F, new Scalar(0));
    }

    public PoseResult findShapeAndPose(Mat findGray, int mAbsoluteFaceSize, Mat mRgba, int width, int height, boolean shapeBlends, Model model, Context context) {
        MatOfRect faces = compModel.findFaces(findGray, mAbsoluteFaceSize);
        DetectionBasedTracker mNativeDetector = compModel.mNativeDetector;
        Rect[] facesArray = faces.toArray();
        final boolean haveFace = facesArray.length > 0;
        Log.i(TAG, "onCameraTexture5 " + haveFace);
        Point center = new Point(0.5, 0.5);
        Point center2 = new Point(0.5, 0.5);
        Point[] foundEyes = null;
        if (haveFace) {
            if (Settings.debugMode) {
                Imgproc.rectangle(mRgba, facesArray[0].tl(), facesArray[0].br(), new Scalar(255, 10 ,10), 3);
            }
            center = OpencvUtils.convertToGl(new Point((2 * facesArray[0].x + facesArray[0].width) / 2.0, (2 * facesArray[0].y + facesArray[0].height) / 2.0), width, height);
            if (mNativeDetector != null) {
                foundEyes = mNativeDetector.findEyes(findGray, facesArray[0]);
                // FIXME temp
                if (Settings.debugMode) {
                    for (Point p : foundEyes) {
                        Imgproc.circle(mRgba, p, 2, new Scalar(255, 10, 10));
                    }
                }
                center = OpencvUtils.convertToGl(new Point((foundEyes[36].x + foundEyes[39].x) / 2.0, (foundEyes[36].y + foundEyes[39].y) / 2.0), width, height);
                center2 = OpencvUtils.convertToGl(new Point((foundEyes[42].x + foundEyes[45].x) / 2.0, (foundEyes[42].y + foundEyes[45].y) / 2.0), width, height);
            }
        }

        Mat glMatrix = null;
        int indexEye = FdActivity2.currentIndexEye;
        Log.i(TAG, "indexEye " + indexEye);
        PoseHelper.bindMatrix(100, 100);

        if (foundEyes != null) {
            if (shapeBlends) {
                Mat inputLandMarks = new Mat(68, 2, CvType.CV_64FC1);
                double[] buff = new double[inputLandMarks.cols() * inputLandMarks.rows()];
                for (int i = 0; i < foundEyes.length; i++) {
                    buff[i * 2] = foundEyes[i].x;
                    buff[i * 2 + 1] = foundEyes[i].y;
                }
                inputLandMarks.put(0, 0, buff);
                Mat output3dShape = new Mat(113, 3, CvType.CV_64FC1);
                if (initialParams == null) {
                    initialParams = new Mat(20, 1, CvType.CV_64FC1, new Scalar(0));
                }
                if (modelPath == null) {
                    if (new File("/storage/extSdCard/models").exists()) {
                        modelPath = "/storage/extSdCard/models";
                    } else {
                        File cascadeDir = context.getDir("models", Context.MODE_PRIVATE);
                        Decompress.unzipFromAssets(context, "models.zip", cascadeDir.getPath());
                        modelPath = cascadeDir.getPath();
                    }
                    Log.i(TAG, "onCameraTexture1 " + modelPath);
                }
                mNativeDetector.morhpFace(inputLandMarks, output3dShape, initialParams, modelPath, true, Settings.useLinear);
                double[] buffShape = new double[output3dShape.cols() * output3dShape.rows()];
                output3dShape.get(0, 0, buffShape);
                int rows = output3dShape.rows();
                for (int i = 0; i < rows; i++) {
                    model.tempV[i * 3] = (float) buffShape[i * 3];
                    model.tempV[i * 3 + 1] = (float) buffShape[i * 3 + 1];
                    model.tempV[i * 3 + 2] = (float) buffShape[i * 3 + 2];
                }
                model.recalcV();
            }
            glMatrix = findPose(model, foundEyes, mRgba);
            //PoseHelper.drawDebug(mRgba, model, glMatrix);
            if (Settings.debugMode) {
                for (Point e : foundEyes) {
                    Imgproc.circle(mRgba, e, 3, new Scalar(255, 255, 255), -1);
                }
            }

        }
        PoseResult result = new PoseResult();
        result.glMatrix = glMatrix;
        result.foundFeatures = foundEyes != null;
        result.leftEye = center;
        result.rightEye = center2;
        return result;
    }

    public Mat findPose(Model model, Point[] foundEyes, Mat mRgba) {
        MatOfDouble distCoeffs = new MatOfDouble();

        for (int i = 0; i < p3d1.length; i++) {
            int p3di = p3d1[i];
            int p2di = p2d1[i];
            // pointsList.add(new Point3(model.tempV[p3di * 3], model.tempV[p3di * 3 + 1], model.tempV[p3di * 3 + 2]));
            pointsListArray[i * 3] = model.tempV[p3di * 3];
            pointsListArray[i * 3 + 1] = model.tempV[p3di * 3 + 1];
            pointsListArray[i * 3 + 2] = model.tempV[p3di * 3 + 2];
            pointsList2Array[i * 2] = foundEyes[p2di].x;
            pointsList2Array[i * 2 + 1] = foundEyes[p2di].y;
        }
        objectPoints.put(0, 0, pointsListArray);
        imagePoints.put(0, 0, pointsList2Array);
        if (true) {
            Calib3d.solvePnP(objectPoints, imagePoints, intrinsics, distCoeffs, rvec, tvec, true, Calib3d.CV_ITERATIVE);
        } else {
            Calib3d.solvePnP(objectPoints, imagePoints, intrinsics, distCoeffs, rvec, tvec);
        }
        if (Settings.debugMode) {
            Log.i("wwww2 rvec", rvec.width() + " " + rvec.height() + " " + rvec.type());
            Imgproc.putText(mRgba, "tvec " + String.format("%.3f", tvec.get(0, 0)[0]) + String.format(" %.3f", tvec.get(1, 0)[0]) + String.format(" %.3f", tvec.get(2, 0)[0]), new Point(50, 100), Core.FONT_HERSHEY_SIMPLEX, 1,
                    new Scalar(255, 255, 255), 2);
            Imgproc.putText(mRgba, "rvec " + String.format("%.3f", rvec.get(0, 0)[0]) + String.format(" %.3f", rvec.get(1, 0)[0]) + String.format(" %.3f", rvec.get(2, 0)[0]), new Point(50, 150), Core.FONT_HERSHEY_SIMPLEX, 1,
                    new Scalar(255, 255, 255), 2);

            MatOfPoint3f objectPoints3 = new MatOfPoint3f();

            MatOfPoint2f imagePoints3 = new MatOfPoint2f();
            java.util.List<Point3> pointsList3 = new ArrayList<Point3>();
            Calib3d.projectPoints(objectPoints, rvec, tvec, intrinsics, distCoeffs, imagePoints3);
            pointsList3 = new ArrayList<Point3>();
            for (int i = 0; i < model.tempV.length / 3; i++) {
                pointsList3.add(new Point3(model.tempV[i * 3], model.tempV[i * 3 + 1], model.tempV[i * 3 + 2]));
            }
            objectPoints3.fromList(pointsList3);
            Calib3d.projectPoints(objectPoints3, rvec, tvec, intrinsics, distCoeffs, imagePoints3);
            Point[] sss = imagePoints3.toArray();
            // debug
            if (Settings.debugMode) {
                for (Point e : sss) {
                    Imgproc.circle(mRgba, e, 3, new Scalar(0, 255, 255), -1);
                }
                // draw main vertices
                pointsList3 = new ArrayList<>();
                pointsList3.add(new Point3(0, 0, 0));
                pointsList3.add(new Point3(1, 0, 0));
                pointsList3.add(new Point3(0, 1, 0));
                pointsList3.add(new Point3(0, 0, 1));
                objectPoints3.fromList(pointsList3);
                Calib3d.projectPoints(objectPoints3, rvec, tvec, intrinsics, distCoeffs, imagePoints3);
                Point[] sss2 = imagePoints3.toArray();
                Imgproc.line(mRgba, sss2[0], sss2[1], new Scalar(255, 0, 0), 2);
                Imgproc.line(mRgba, sss2[0], sss2[2], new Scalar(0, 255, 0), 2);
                Imgproc.line(mRgba, sss2[0], sss2[3], new Scalar(0, 0, 255), 2);
            }
        }

        Calib3d.Rodrigues(rvec, rotation);

        for (int row = 0; row < 3; ++row) {
            for(int col = 0; col < 3; ++col) {
                viewMatrix.put(row, col, rotation.get(row, col)[0]);
            }
            viewMatrix.put(row, 3, tvec.get(row, 0)[0]);
        }
        viewMatrix.put(3, 3, 1);
        if (Settings.debugMode) {
            drawDebug(mRgba, model, viewMatrix, intrinsics);
        }

        Mat viewMatrix2 = new Mat(4, 4, CvType.CV_64F, new Scalar(0));

        Core.gemm(cvToGl, viewMatrix, 1, new Mat(), 0, viewMatrix2);

        Mat glViewMatrix = new Mat(4, 4, CvType.CV_64F, new Scalar(0));
        Core.transpose(viewMatrix2 , glViewMatrix);
        return glViewMatrix;
    }

    public static float[] convertToArray(Mat s) {
        float[] matrixArray = new float[16];
        for(int row=0; row<4; ++row)
        {
            for(int col=0; col<4; ++col)
            {
                matrixArray[row * 4 + col] = (float)s.get(row, col)[0];
            }
        }
        return matrixArray;
    }

    public static void drawDebug(Mat mRgba, Model model, Mat viewMatrix2, Mat intrinsics) {
        Mat viewMatrix = viewMatrix2.submat(0, 3, 0 , 4);
        Point[] points = new Point[model.tempV.length / 3];
        for (int c  = 0; c <  model.tempV.length / 3; c++) {
            Mat mat4 = new Mat(4, 1, CvType.CV_64F);
            mat4.put(0, 0, model.tempV[c * 3]);
            mat4.put(1, 0, model.tempV[c * 3 + 1]);
            mat4.put(2, 0, model.tempV[c * 3 + 2]);
            mat4.put(3, 0, 1);
            Mat matRes = new Mat();
            Core.gemm(viewMatrix, mat4, 1, new Mat(), 0, matRes, 0);


//            Mat matRes2 = new Mat();
//            Log.i("wwww", intrinsics.size().width + " " + intrinsics.size().height + " " + matRes.size().width + " " + matRes.size().height);
//            Core.gemm(intrinsics, matRes, 1, new Mat(), 0, matRes2, 0);

            double z = matRes.get(2, 0)[0];
            if (z != 0) {
                z = 1.0/z;
            } else z = 1;
            double x = matRes.get(0, 0)[0]  * z;
            double y = matRes.get(1, 0)[0] * z;

            x = x * intrinsics.get(0, 0)[0] + y * intrinsics.get(0, 1)[0] + intrinsics.get(0, 2)[0];
            y = x * intrinsics.get(1, 0)[0] + y * intrinsics.get(1, 1)[0] + intrinsics.get(1, 2)[0];


            points[c] = new Point(x, y);
            Imgproc.circle(mRgba, new Point(x, y), 3, new Scalar(255, 255, 255), -1);
        }
        for (int indi = 0; indi < model.indices.length / 3; indi++) {
            Imgproc.line(mRgba, points[model.indices[indi * 3]], points[model.indices[indi * 3 + 1]], new Scalar(255, 255, 0));
            Imgproc.line(mRgba, points[model.indices[indi * 3 + 1]], points[model.indices[indi * 3 + 2]], new Scalar(255, 255, 0));
            Imgproc.line(mRgba, points[model.indices[indi * 3 + 2]], points[model.indices[indi * 3]], new Scalar(255, 255, 0));
        }
    }

    public static float[] createProjectionMatrix(int width, int height) {
        float[] mProjectionMatrix = new float[16];
        float ratio = 1;
        float left = -0.5f;
        float right = 0.5f;
        float bottom = -0.5f;
        float top = 0.5f;
        float near = 0.5f;
        float far = 4;
        if (width > height) {
            ratio = (float) width / height;
            left *= ratio;
            right *= ratio;
        } else {
            ratio = (float) height / width;
            bottom *= ratio;
            top *= ratio;
        }
        Matrix.frustumM(mProjectionMatrix, 0, left, right, bottom, top, near, far);
        return mProjectionMatrix;
    }

    public static float[] createProjectionMatrixThroughPerspective(int width, int height) {
        float[] mProjectionMatrix = new float[16];
        Matrix.perspectiveM(mProjectionMatrix, 0, 83.26707f, (float)width / (float)height, 0.01f, 300.0f); // Specifies the field of view angle, in degrees, in the y direction. atan(0.5 * height/ width) * 2
        return mProjectionMatrix;
    }


    private static float[] createViewMatrix() {
        float[] mViewMatrix = new float[16];
        // точка положения камеры
        float eyeX = 0;
        float eyeY = 0;
        float eyeZ = 3;

        // точка направления камеры
        float centerX = 0;
        float centerY = 0;
        float centerZ = 0;

        // up-вектор
        float upX = 0;
        float upY = 1;
        float upZ = 0;

        Matrix.setLookAtM(mViewMatrix, 0, eyeX, eyeY, eyeZ, centerX, centerY, centerZ, upX, upY, upZ);
        return mViewMatrix;
    }


    public static float[] bindMatrix(int width, int height) {
        float[] mMatrix = new float[16];
        Matrix.multiplyMM(mMatrix, 0, createProjectionMatrix(width, height), 0, createViewMatrix(), 0);
        return mMatrix;
    }


    public static class PoseResult {
        public Mat glMatrix;
        public boolean foundFeatures;
        public Point leftEye;
        public Point rightEye;
    }

}
