// FFmpegSdlTest.cpp : 定义控制台应用程序的入口点。
//

#include "stdafx.h"

extern "C"
{
#include "sdl/SDL.h"
#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libswscale/swscale.h"
}
AVFormatContext	*pFormatCtx;
int				i, videoindex;

AVCodecContext	*pCodecCtx;

AVCodec			*pCodec;

AVPacket *packet;

AVFrame	*pFrame,*pFrameYUV;
uint8_t *out_buffer;
int y_size;
int ret, got_picture;
struct SwsContext *img_convert_ctx;

//char filepath[]="Titanic.ts";
//char filepath[500] ={0};
//SDL相关
int screen_w=500,screen_h=500;
//Refresh Event
#define REFRESH_EVENT  (SDL_USEREVENT + 1)
//Break
#define BREAK_EVENT  (SDL_USEREVENT + 2)

int thread_exit=0;

int refresh_video(void *opaque){
	thread_exit=0;
	while (thread_exit==0) {
		SDL_Event event;
		event.type = REFRESH_EVENT;
		SDL_PushEvent(&event);
		SDL_Delay(40);
	}
	thread_exit=0;
	//Break
	SDL_Event event;
	event.type = BREAK_EVENT;
	SDL_PushEvent(&event);
	return 0;
}
//注意修改为 main 默认为t_main 
//如果是调试测试 也可以在项目调试 附加命令中填入命令，例如本例子中视频文件路径
int main(int argc, char* argv[])
{
	//strcpy(filepath,argv[0]);
	char*filepath = argv[1];
	av_register_all();
	avformat_network_init();
	pFormatCtx = avformat_alloc_context();

	if(avformat_open_input(&pFormatCtx,filepath,NULL,NULL)!=0){
		printf("Couldn't open input stream.\n");
		return -1;
	}

	if(avformat_find_stream_info(pFormatCtx,NULL)<0){
		printf("Couldn't find stream information.\n");
		return -1;
	}


	videoindex=-1; 
	for(i=0; i<pFormatCtx->nb_streams; i++) 
		if(pFormatCtx->streams[i]->codec->codec_type==AVMEDIA_TYPE_VIDEO){
			videoindex=i;
			break;
		}
		if(videoindex==-1){
			printf("Didn't find a video stream.\n");
			return -1;
		}

	pCodecCtx=pFormatCtx->streams[videoindex]->codec;

	pCodec=avcodec_find_decoder(pCodecCtx->codec_id);
	if(pCodec==NULL){
		printf("Codec not found.\n");
		return -1;
	}

	if(avcodec_open2(pCodecCtx, pCodec,NULL)<0){
		printf("Could not open codec.\n");
		return -1;
	}

	pFrame=av_frame_alloc();

	pFrameYUV=av_frame_alloc();
	printf("wirdth %d",pFrameYUV->width);
	out_buffer=(uint8_t *)av_malloc(avpicture_get_size(PIX_FMT_YUV420P, pCodecCtx->width, pCodecCtx->height));
	avpicture_fill((AVPicture *)pFrameYUV, out_buffer, PIX_FMT_YUV420P, pCodecCtx->width, pCodecCtx->height);
	packet=(AVPacket *)av_malloc(sizeof(AVPacket));
	//Output Info-----------------------------
	printf("--------------- File Information ----------------\n");
	av_dump_format(pFormatCtx,0,filepath,0);
	printf("-------------------------------------------------\n");
	img_convert_ctx = sws_getContext(pCodecCtx->width, pCodecCtx->height, pCodecCtx->pix_fmt, 
		pCodecCtx->width, pCodecCtx->height, PIX_FMT_YUV420P, SWS_BICUBIC, NULL, NULL, NULL); 


	//SDL相关
	if(SDL_Init(SDL_INIT_VIDEO|SDL_INIT_TIMER)) {  
		printf( "Could not initialize SDL - %s\n", SDL_GetError()); 
		return -1;
	} 

	SDL_Window *screen; 
	screen_w =pCodecCtx->width;
	screen_h =pCodecCtx->height;
	//SDL 2.0 Support for multiple windows
	screen = SDL_CreateWindow("Simplest Video Play SDL2", SDL_WINDOWPOS_UNDEFINED, SDL_WINDOWPOS_UNDEFINED,
		screen_w, screen_h,SDL_WINDOW_OPENGL|SDL_WINDOW_RESIZABLE);
	if(!screen) {  
		printf("SDL: could not create window - exiting:%s\n",SDL_GetError());  
		return -1;
	}
	SDL_Renderer* sdlRenderer = SDL_CreateRenderer(screen, -1, 0);  

	Uint32 pixformat=0;
	//IYUV: Y + U + V  (3 planes)
	//YV12: Y + V + U  (3 planes)
	pixformat= SDL_PIXELFORMAT_IYUV;  

	SDL_Texture* sdlTexture = SDL_CreateTexture(sdlRenderer,pixformat, SDL_TEXTUREACCESS_STREAMING,pCodecCtx->width,pCodecCtx->height);


	SDL_Rect sdlRect;  

	SDL_Thread *refresh_thread = SDL_CreateThread(refresh_video,NULL,NULL);
	SDL_Event event;

	//FIX: If window is resize
	sdlRect.x = 0;  
	sdlRect.y = 0;  
	sdlRect.w = screen_w;  
	sdlRect.h = screen_h; 

	while(av_read_frame(pFormatCtx, packet)>=0){
		if(packet->stream_index==videoindex){
			ret = avcodec_decode_video2(pCodecCtx, pFrame, &got_picture, packet);
			if(ret < 0){
				printf("Decode Error.\n");
				return -1;
			}
				SDL_WaitEvent(&event);
				if(event.type==REFRESH_EVENT){
					if(got_picture){
						sws_scale(img_convert_ctx, (const uint8_t* const*)pFrame->data, pFrame->linesize, 0, pCodecCtx->height, 
								pFrameYUV->data, pFrameYUV->linesize);
						SDL_UpdateTexture( sdlTexture, NULL, pFrameYUV->data[0], pFrameYUV->linesize[0]);  
						SDL_RenderClear( sdlRenderer );   
						SDL_RenderCopy( sdlRenderer, sdlTexture, NULL, &sdlRect);  
						SDL_RenderPresent( sdlRenderer ); 
					}

				}
			}
	}
	//fclose(fp_frame);
	sws_freeContext(img_convert_ctx);

	av_frame_free(&pFrameYUV);
	av_frame_free(&pFrame);

	avcodec_close(pCodecCtx);
	avformat_close_input(&pFormatCtx);
	SDL_Quit();
	return 0;
}

