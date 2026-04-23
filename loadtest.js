import http from 'k6/http';
import { sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 20 },
    { duration: '2m',  target: 100 },
    { duration: '30s', target: 0 },
  ],
};

export default function () {
  // Use host.docker.internal instead of localhost
  http.get('http://host.docker.internal:8081/api/compute?iterations=20000');
  sleep(0.2);

  http.post(
    'http://host.docker.internal:8081/api/heavy-task',
    JSON.stringify({ size: 5000 }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  sleep(0.2);

  http.get('http://host.docker.internal:8081/api/hello');
  sleep(0.1);
}