interface Props {
  message: string;
  onRetry?: () => void;
}

export function ErrorState({ message, onRetry }: Props) {
  return (
    <div>
      <p role="alert" className="error">
        {message}
      </p>
      {onRetry && (
        <button type="button" onClick={onRetry}>
          Thử lại
        </button>
      )}
    </div>
  );
}
