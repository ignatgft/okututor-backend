// k6 load test: поиск курсов Okututor (этап 6).
// Запуск:  k6 run k6/search-load-test.js --env BASE_URL=http://localhost:8080
// Цели (спека #28): p50 < 200ms, p95 < 500ms при 50 RPS.

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    search_mix: {
      executor: 'constant-arrival-rate',
      rate: 50,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 20,
      maxVUs: 60,
    },
  },
  thresholds: {
    http_req_duration: ['p(50)<200', 'p(95)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

// типичные запросы: RU/EN, синонимы, естественный язык, фильтры, каталог
const QUERIES = [
  'математика',
  'английский',
  'репетитор по математике для подготовки к ОРТ 10 класс онлайн до 1000 сом',
  'курс Java для начинающих',
  'англис тили мугалими',
  'питон',
  'english tutor',
  'it',
];

export default function () {
  const q = QUERIES[Math.floor(Math.random() * QUERIES.length)];
  const eq = encodeURIComponent(q);

  // основной поиск (URL строится вручную — k6 не сериализует `params` в query-string этой сборки)
  const search = http.get(`${BASE_URL}/api/v1/search/courses?q=${eq}&page=0&size=20`, {
    tags: { name: 'search_courses' },
  });
  check(search, { 'search 200': (r) => r.status === 200 });

  // поиск с hard-фильтрами
  const filtered = http.get(`${BASE_URL}/api/v1/search/courses?q=${eq}&max_price=2000&rating_min=1&page=0&size=20`, {
    tags: { name: 'search_filtered' },
  });
  check(filtered, { 'filtered 200': (r) => r.status === 200 });

  // каталог без q
  const catalog = http.get(`${BASE_URL}/api/v1/search/courses?max_price=3000&page=0&size=20`, {
    tags: { name: 'catalog' },
  });
  check(catalog, { 'catalog 200': (r) => r.status === 200 });

  // suggestions
  const suggestions = http.get(`${BASE_URL}/api/v1/search/suggestions?q=${eq}`, {
    tags: { name: 'suggestions' },
  });
  check(suggestions, { 'suggestions 200': (r) => r.status === 200 });

  sleep(0.1);
}
