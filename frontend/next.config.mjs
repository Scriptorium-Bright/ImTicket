/** @type {import('next').NextConfig} */
const nextConfig = {
    images: {
        remotePatterns: [
            {
                protocol: 'https',
                hostname: 'images.unsplash.com',
            },
        ],
    },
    async rewrites() {
        return [
            {
                source: '/api/group/:path*',
                destination: 'http://localhost:7001/api/group/:path*',
            },
            {
                source: '/api/ticket/:path*',
                destination: 'http://localhost:7001/api/ticket/:path*',
            },
            {
                source: '/api/:path*',
                destination: 'http://localhost:10080/api/:path*',
            },
        ];
    },
};

export default nextConfig;
