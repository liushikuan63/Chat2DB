import type { TaskControlState } from '@/service/importExport';

interface TaskCancellationServices {
  getTaskControl: (params: { taskId: number }) => Promise<TaskControlState>;
  cancelTask: (params: { taskId: number }) => Promise<TaskControlState>;
}

export async function requestTaskCancellation(taskId: number, services: TaskCancellationServices) {
  const state = await services.getTaskControl({ taskId });
  if (state.taskId !== taskId) throw new Error('Task control response does not match the selected task');
  if (!state.cancelSupported) return state;
  const result = await services.cancelTask({ taskId });
  if (result.taskId !== taskId) throw new Error('Task cancellation response does not match the selected task');
  return result;
}
